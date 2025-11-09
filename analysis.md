# Полный анализ проблем NotEnoughIds для Ultramine Core Master Branch

## Дата анализа
2025-11-09

## Оглавление
1. [Описание проблемы](#описание-проблемы)
2. [Анализ архитектуры](#анализ-архитектуры)
3. [Критические изменения Ultramine Master Branch](#критические-изменения-ultramine-master-branch)
4. [Корневые причины проблем](#корневые-причины-проблем)
5. [Предложенные решения](#предложенные-решения)
6. [Детальный план исправления](#детальный-план-исправления)

---

## Описание проблемы

### Наблюдаемые симптомы

1. **При первом входе клиента на сервер:**
   - Чанки отображаются частично корректно
   - Присутствуют дыры в чанках (пустые области)
   - Случайные блоки появляются на неверных позициях

2. **При втором входе на сервер:**
   - Сплошные случайные/неправильные блоки
   - ПКМ (правый клик мыши) по блоку "исправляет" его - блок становится таким, каким должен быть
   - Блоки воздуха также реагируют на ПКМ и превращаются в правильные блоки

3. **После перезагрузки сервера:**
   - Все ранее загруженные клиентом чанки полностью исчезают
   - Нет чанков, нет физики
   - Никакой реакции на ПКМ

### Контекст
Проект NotEnoughIds был адаптирован для работы с ядром Ultramine Core ветки master, которое использует:
- Off-heap хранение данных чанков (MemSlot)
- Оптимизированную систему сохранения через EbsSaveFakeNbt
- ChunkSnapshot для асинхронной отправки пакетов

---

## Анализ архитектуры

### Ultramine Core Master Branch - ключевые компоненты

#### 1. MemSlot - Off-heap хранение блоков

**Unsafe7MemSlot (layout 7, по умолчанию):**
```
Offset 0:     LSB (4096 bytes)      - Младшие 8 бит ID блока
Offset 4096:  MSB (2048 bytes)      - Старшие 4 бита ID блока (nibble array)
Offset 6144:  META (2048 bytes)     - Метаданные блоков (nibble array)
Offset 8192:  BLOCKLIGHT (2048 b)   - Освещение от блоков (nibble array)
Offset 10240: SKYLIGHT (2048 b)     - Небесное освещение (nibble array)

Итого: 12,288 байт на секцию чанка (16x16x16 блоков)
```

**Индексация координат:**
```java
int index = y << 8 | z << 4 | x;  // YZX порядок для лучшей локальности кэша
```

**Ключевые особенности:**
- Память выделяется через `sun.misc.Unsafe.allocateMemory()`
- Освобождение с задержкой 5 секунд (защита от data races)
- Лимит: 6GB off-heap памяти (по умолчанию)

#### 2. UnsafeChunkAlloc - Менеджер памяти

```java
// UnsafeChunkAlloc.java:24-110
public synchronized MemSlot allocateSlot() {
    ReleasedSlot released = releasedSlots.poll();
    if(released != null)
        return slotFactory.apply(released.pointer);  // ПЕРЕИСПОЛЬЗОВАНИЕ!

    slots++;
    if(slots >= SLOT_LIMIT)
        throw new OutOfMemoryError("Off-heap chunk storage");

    return slotFactory.apply(U.allocateMemory(SLOT_SIZE));
}

synchronized void releaseSlot(long pointer) {
    releasedSlots.add(new ReleasedSlot(pointer));  // Добавляется в очередь
}

private void releaseAvailableSlots() {
    // Запускается каждые 2 секунды в отдельном потоке
    long time = System.currentTimeMillis();
    while(true) {
        ReleasedSlot slot = releasedSlots.peek();
        if(slot == null || time - slot.time < SLOT_FREE_DELAY)  // 5000ms
            break;
        releasedSlots.poll();
        toRelease.add(slot.pointer);
    }

    for(TLongIterator it = toRelease.iterator(); it.hasNext();)
        U.freeMemory(it.next());  // Реальное освобождение
}
```

**КРИТИЧНО:** Между `releaseSlot()` и реальным `freeMemory()` проходит до 5+ секунд, за это время pointer может быть переиспользован для нового чанка!

#### 3. ExtendedBlockStorage - интеграция с MemSlot

```java
// ExtendedBlockStorage.java:15-44
public class ExtendedBlockStorage {
    private int yBase;
    private int blockRefCount;
    private int tickRefCount;
    private volatile MemSlot slot;  // Off-heap данные

    public ExtendedBlockStorage copy() {
        slot.getClass(); //NPE check
        return new ExtendedBlockStorage(slot.copy(), yBase, blockRefCount, tickRefCount);
    }

    public void release() {
        MemSlot slotLocal = this.slot;
        this.slot = null;
        slotLocal.release();  // Добавляется в releasedSlots queue!
    }
}
```

### NotEnoughIds - расширение до 16-bit блоков

**Основная концепция:**
- Vanilla Minecraft: 12-bit блоки (0-4095)
- NEID: 16-bit блоки (0-32767)

**Реализация:**
- Добавляет в `ExtendedBlockStorage` два массива: `block16BArray` и `block16BMetaArray`
- Использует собственные миксины для перехвата операций с блоками
- Изменяет формат сетевых пакетов и NBT сохранения

---

## Критические изменения Ultramine Master Branch

### Коммит 1: Off-heap chunk storage (5f8a83c)

**Изменения:**
1. Введён интерфейс `MemSlot` с двумя реализациями (Layout 7 и 8)
2. `UnsafeChunkAlloc` с delayed release (5 секунд)
3. `ExtendedBlockStorage.copy()` копирует MemSlot целиком
4. Замена `free()` на `release()` во всех местах

**Влияние на NEID:**
- MemSlot использует 12-bit ID (8-bit LSB + 4-bit MSB)
- NEID использует 16-bit ID
- Необходима синхронизация между MemSlot и NEID массивами

### Коммит 2: Оптимизация отправки чанков (367fafa)

**Ключевое нововведение - ChunkSnapshot:**

```java
// ChunkSnapshot.java:209-217
public static ChunkSnapshot of(Chunk chunk) {
    ExtendedBlockStorage[] ebsOld = chunk.getBlockStorageArray();
    ExtendedBlockStorage[] ebsNew = new ExtendedBlockStorage[ebsOld.length];
    for(int i = 0; i < ebsOld.length; i++)
        ebsNew[i] = ebsOld[i] == null ? null : ebsOld[i].copy();  // КОПИРУЕТ MemSlot!
    byte[] biomeArray = chunk.getBiomeArray();
    return new ChunkSnapshot(chunk.xPosition, chunk.zPosition,
                             chunk.worldObj.provider.hasNoSky, ebsNew,
                             Arrays.copyOf(biomeArray, biomeArray.length));
}

public void release() {
    for(ExtendedBlockStorage ebs : ebsArr)
        if(ebs != null)
            ebs.release();  // ОСВОБОЖДАЕТ ВСЕ СКОПИРОВАННЫЕ MemSlot!
}
```

**Процесс отправки чанка:**

```java
// ChunkSendManager.java:157-158
this.chunkSnapshot = ChunkSnapshot.of(chunkId.chunk); // Синхронно, в главном потоке

// В отдельном потоке:
public void run() {
    S21PacketChunkData packet = S21PacketChunkData.makeForSend(chunkSnapshot);
    packet.deflate();  // chunkSnapshot.release() вызывается ЗДЕСЬ!
}
```

**ПРОБЛЕМА:**
1. ChunkSnapshot создаётся синхронно, копируя все MemSlot
2. Пакет обрабатывается асинхронно
3. После `deflate()` все MemSlot освобождаются → добавляются в `releasedSlots`
4. Через 5 секунд память может быть переиспользована
5. Но если за эти 5 секунд создаётся новый чанк, он может получить тот же pointer!

### Коммит 3: Оптимизация сохранения мира (e373b5a)

**Ключевое нововведение - EbsSaveFakeNbt:**

```java
// EbsSaveFakeNbt.java:25-56
public class EbsSaveFakeNbt extends NBTTagCompound implements ReferenceCounted {
    private final ExtendedBlockStorage ebs;  // Ссылка, НЕ копия!
    private final boolean hasNoSky;
    private volatile boolean isNbt;
    private volatile int refCnt = 1;

    public void convertToNbt() {
        if(isNbt) return;
        createMap(0);
        setByte("Y", (byte)(ebs.getYLocation() >> 4 & 255));
        MemSlot slot = ebs.getSlot();  // Читаем из MemSlot напрямую
        setByteArray("Blocks", slot.copyLSB());
        setByteArray("Add", slot.copyMSB());
        // ... и т.д.
        isNbt = true;
    }

    public void write(DataOutput out) throws IOException {
        if(isNbt) {
            super.write(out);
            return;
        }
        // Читаем напрямую из MemSlot БЕЗ копирования!
        MemSlot slot = ebs.getSlot();
        byte[] buf = LOCAL_BUFFER.get();
        slot.copyLSB(buf, 0);
        writeByteArray(out, "Blocks", buf, 0, 4096);
        // ... и т.д.
    }
}
```

**Reference counting:**

```java
private void deallocate() {
    ebs.release();  // Вызывается когда refCnt достигает 0
}
```

**Процесс сохранения:**

```java
// AnvilChunkLoader.java:378-387
for (int k = 0; k < 16; ++k) {
    ExtendedBlockStorage extendedblockstorage = aextendedblockstorage[k];
    if (extendedblockstorage != null) {
        nbttaglist.appendTag(new EbsSaveFakeNbt(extendedblockstorage.copy(), !flag));
    }
}
```

**ПРОБЛЕМА:**
1. `EbsSaveFakeNbt` создаётся с копией EBS (которая имеет скопированный MemSlot)
2. NBT добавляется в асинхронную очередь сохранения
3. Запись может произойти через несколько тиков
4. К моменту вызова `write()` или `convertToNbt()` MemSlot может быть уже освобождён и переиспользован!

---

## Корневые причины проблем

### Проблема #1: Race Condition при отправке пакетов

**Сценарий:**

```
Время  | Главный поток                    | Асинхронный поток             | UnsafeChunkAlloc
-------|----------------------------------|-------------------------------|------------------
T=0    | Создаётся chunk A                |                               | Выделяет ptr=0x1000
       | MemSlot A @ 0x1000               |                               |
-------|----------------------------------|-------------------------------|------------------
T=1    | ChunkSnapshot.of(chunk A)        |                               |
       | Копирует MemSlot A → 0x2000      |                               | Выделяет ptr=0x2000
-------|----------------------------------|-------------------------------|------------------
T=2    |                                  | deflate() начинается          |
       |                                  | Читает из 0x2000 ✓            |
-------|----------------------------------|-------------------------------|------------------
T=3    |                                  | deflate() завершается         |
       |                                  | chunkSnapshot.release()       |
       |                                  | → MemSlot 0x2000 освобождён   | 0x2000 → releasedSlots queue
-------|----------------------------------|-------------------------------|------------------
T=4    | Создаётся chunk B                |                               | Переиспользует 0x2000!
       | MemSlot B @ 0x2000               |                               | (т.к. < 5 секунд)
-------|----------------------------------|-------------------------------|------------------
T=5    |                                  | Пакет для chunk A отправляется|
       |                                  | НО в 0x2000 уже данные chunk B!| ❌ ДАННЫЕ CHUNK B!
-------|----------------------------------|-------------------------------|------------------
```

**Результат:** Клиент получает пакет для chunk A, но с данными chunk B → рандомные блоки!

### Проблема #2: MixinS21PacketChunkDataUltramine читает из освобождённого MemSlot

**Текущая реализация:**

```java
// MixinS21PacketChunkDataUltramine.java:38-80
@Overwrite
public static S21PacketChunkData.Extracted func_149269_a(Chunk chunk, boolean fullChunk, int sectionMask) {
    // Получает EBS из оригинального chunk, НЕ из ChunkSnapshot!
    ExtendedBlockStorage[] ebsArray = chunk.getBlockStorageArray();

    // ... создаёт neidData ...
    byte[] neidData = createNeidFormatData(ebsArray, ebsMask, fullChunk, chunk);
    // ...
}

private static byte[] createNeidFormatData(ExtendedBlockStorage[] ebsArray, ...) {
    for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
        ExtendedBlockStorage ebs = ebsArray[sectionIndex];
        Object slot = getSlot(ebs);  // Получает MemSlot из оригинального EBS!

        byte[] lsb = new byte[4096];
        byte[] msb = new byte[2048];
        copyFromSlot(slot, "copyLSB", lsb);  // Читает из MemSlot
        copyFromSlot(slot, "copyMSB", msb);
        // ...
    }
}
```

**ПРОБЛЕМА:** Ultramine передаёт в `S21PacketChunkData` ChunkSnapshot, а не оригинальный chunk!

```java
// ChunkSendManager.java (ULTRAMINE):
public CompressAndSendChunkTask(ChunkIdStruct chunkId) {
    this.chunkId = chunkId;
    this.chunkSnapshot = ChunkSnapshot.of(chunkId.chunk);  // Копия!
}

public void run() {
    S21PacketChunkData packet = S21PacketChunkData.makeForSend(chunkSnapshot);
    packet.deflate();  // Освобождает chunkSnapshot
}
```

Но `MixinS21PacketChunkDataUltramine` ожидает оригинальный chunk, а получает ChunkSnapshot, который уже освобождён!

### Проблема #3: EbsSaveFakeNbt и NEID несовместимы

**Цепочка вызовов при сохранении:**

```
AnvilChunkLoader.writeChunkToNBT()
  └─> new EbsSaveFakeNbt(ebs.copy(), !flag)  // Создаётся с копией!
        └─> NBTTagList.appendTag(ebsSaveFakeNbt)
              └─> addChunkToPending()  // Добавляется в асинхронную очередь
                    └─> ThreadedFileIOBase.queueIO()

... несколько тиков спустя ...

ThreadedFileIOBase (другой поток)
  └─> writeNextIO()
        └─> CompressedStreamTools.write(nbt, ...)
              └─> EbsSaveFakeNbt.write()  // MemSlot может быть уже освобождён!
```

**Текущая попытка исправления:**

```java
// MixinEbsSaveFakeNbt.java:176-185
@Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
private void neid$forceConvertAfterInit(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
    LOGGER.info("INJECT <init>: Force converting to NEID NBT format immediately.");
    convertToNbt();  // Немедленная конвертация
    LOGGER.info("INJECT <init>: Completed convertToNbt(), isNbt={}", isNbt);
}
```

**ПРОБЛЕМА:** `convertToNbt()` копирует данные из MemSlot в NBT массивы, но:
1. Это происходит синхронно в главном потоке
2. Создаёт дополнительные аллокации (копии данных)
3. Если конвертация происходит ДО того, как MemSlot заполнен данными → пустые массивы!

### Проблема #4: ПКМ "исправляет" блоки

**Почему это происходит:**

```java
// Клиент отправляет пакет C08PacketPlayerBlockPlacement при ПКМ
// Сервер обрабатывает:
ItemInWorldManager.activateBlockOrUseItem()
  └─> Block.onBlockActivated()
        └─> Если блок неправильный, сервер отправляет S23PacketBlockChange
              └─> Клиент заменяет блок на правильный!
```

**Это "фикс симптома, а не причины"** - сервер знает правильный блок (он хранится в MemSlot корректно), но клиент получил неправильный при первоначальной загрузке чанка.

### Проблема #5: Чанки исчезают после перезагрузки

**Сценарий:**

```
1. Сервер запускается первый раз
   └─> Чанки загружаются из файлов или генерируются
       └─> MemSlot заполняется правильными данными

2. Клиент подключается
   └─> S21PacketChunkData отправляется с данными из освобождённого MemSlot
       └─> Клиент получает неправильные/случайные данные

3. Сервер сохраняет мир и выключается
   └─> EbsSaveFakeNbt.write() читает из MemSlot
       └─> Если MemSlot уже освобождён → записываются неправильные данные в файл!

4. Сервер запускается снова
   └─> Загружает неправильные данные из файла
       └─> MemSlot содержит мусор
       └─> Клиент получает пустые/нулевые чанки
```

**КРИТИЧНО:** Ultramine сохраняет данные из MemSlot напрямую через `EbsSaveFakeNbt`, минуя NEID. Если MemSlot освобождён → записывается мусор → мир коррумпируется!

---

## Предложенные решения

### Решение #1: Немедленное копирование данных из ChunkSnapshot

**Проблема:** `MixinS21PacketChunkDataUltramine` должен читать из ChunkSnapshot, а не из оригинального chunk.

**Решение:**

```java
// MixinS21PacketChunkDataUltramine.java (ИСПРАВИТЬ)
@Overwrite
public static S21PacketChunkData.Extracted func_149269_a(Chunk chunk, boolean fullChunk, int sectionMask) {
    LOGGER.info("func_149269_a() called for chunk ({},{})", chunk.xPosition, chunk.zPosition);

    // КРИТИЧНО: Если chunk является ChunkSnapshot, получаем EBS из НЕГО!
    ExtendedBlockStorage[] ebsArray;
    boolean worldHasNoSky;

    if (chunk instanceof org.ultramine.server.chunk.ChunkSnapshot) {
        org.ultramine.server.chunk.ChunkSnapshot snapshot =
            (org.ultramine.server.chunk.ChunkSnapshot) chunk;
        ebsArray = snapshot.getEbsArr();
        worldHasNoSky = snapshot.isWorldHasNoSky();
    } else {
        ebsArray = chunk.getBlockStorageArray();
        worldHasNoSky = chunk.worldObj.provider.hasNoSky;
    }

    // КРИТИЧНО: Немедленно копируем ВСЕ данные из MemSlot!
    byte[] neidData = createNeidFormatDataImmediately(ebsArray, ebsMask, fullChunk, worldHasNoSky);

    extracted.field_150282_a = neidData;
    return extracted;
}

private static byte[] createNeidFormatDataImmediately(ExtendedBlockStorage[] ebsArray,
                                                       int ebsMask,
                                                       boolean fullChunk,
                                                       boolean worldHasNoSky) {
    // Копируем данные НЕМЕДЛЕННО, пока MemSlot ещё валиден
    for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
        if ((ebsMask & (1 << sectionIndex)) == 0) continue;

        ExtendedBlockStorage ebs = ebsArray[sectionIndex];
        Object slot = getSlot(ebs);

        // Копируем ВСЕ данные сразу, пока метод выполняется
        byte[] lsb = new byte[4096];
        byte[] msb = new byte[2048];
        byte[] meta = new byte[2048];

        copyFromSlot(slot, "copyLSB", lsb);
        copyFromSlot(slot, "copyMSB", msb);
        copyFromSlot(slot, "copyBlockMetadata", meta);

        // Конвертируем в NEID формат и записываем в data[]
        // ...
    }

    return data;
}
```

**НО ЕСТЬ ПРОБЛЕМА:** ChunkSnapshot не наследуется от Chunk! Нужен другой подход.

### Решение #2: Перехват создания S21PacketChunkData

**Ultramine создаёт пакет так:**

```java
// S21PacketChunkData.java (ULTRAMINE):
public static S21PacketChunkData makeForSend(ChunkSnapshot chunkSnapshot) {
    return new S21PacketChunkData(chunkSnapshot);
}

private S21PacketChunkData(ChunkSnapshot chunkSnapshot) {
    this.field_149284_a = chunkSnapshot.getX();
    this.field_149282_b = chunkSnapshot.getZ();
    this.field_149279_g = true;
    this.chunkSnapshot = chunkSnapshot;  // Сохраняет ссылку!
}
```

**РЕШЕНИЕ:** Добавить mixin, который перехватывает этот конструктор!

```java
// НОВЫЙ ФАЙЛ: MixinS21PacketChunkDataSnapshot.java
@Mixin(value = S21PacketChunkData.class, priority = 1600)
public class MixinS21PacketChunkDataSnapshot {

    @Shadow private ChunkSnapshot chunkSnapshot;
    @Shadow private byte[] field_149278_f;  // Vanilla data field
    @Shadow private int field_149283_c;     // EBS mask
    @Shadow private int field_149280_d;     // EBS mask 2

    /**
     * НЕМЕДЛЕННО после создания пакета с ChunkSnapshot,
     * копируем ВСЕ данные из MemSlot в field_149278_f!
     *
     * Это гарантирует, что данные скопированы ДО того,
     * как ChunkSnapshot.release() освободит MemSlot.
     */
    @Inject(method = "<init>(Lorg/ultramine/server/chunk/ChunkSnapshot;)V",
            at = @At("RETURN"),
            require = 0,
            remap = false)
    private void neid$extractDataImmediately(ChunkSnapshot snapshot, CallbackInfo ci) {
        LOGGER.info("Extracting NEID data from ChunkSnapshot immediately!");

        ExtendedBlockStorage[] ebsArray = snapshot.getEbsArr();

        // Вычисляем EBS mask
        int ebsMask = 0;
        for (int i = 0; i < ebsArray.length; i++) {
            if (ebsArray[i] != null && !ebsArray[i].isEmpty()) {
                ebsMask |= 1 << i;
            }
        }

        this.field_149283_c = ebsMask;
        this.field_149280_d = ebsMask;

        // КРИТИЧНО: Копируем ВСЕ данные ПРЯМО СЕЙЧАС
        this.field_149278_f = createNeidFormatFromSnapshot(snapshot, ebsArray, ebsMask);

        // Очищаем ссылку на snapshot - он больше не нужен
        this.chunkSnapshot = null;

        LOGGER.info("NEID data extracted, size={} bytes", field_149278_f.length);
    }

    private static byte[] createNeidFormatFromSnapshot(ChunkSnapshot snapshot,
                                                        ExtendedBlockStorage[] ebsArray,
                                                        int ebsMask) {
        int ebsCount = Integer.bitCount(ebsMask);
        int totalSize = ebsCount * Constants.BYTES_PER_EBS + 256;  // +256 для biome
        byte[] data = new byte[totalSize];
        int offset = 0;

        // Копируем все блоки, метаданные, освещение из MemSlot
        for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
            if ((ebsMask & (1 << sectionIndex)) == 0) continue;

            ExtendedBlockStorage ebs = ebsArray[sectionIndex];
            Object slot = getSlot(ebs);

            // Копируем LSB, MSB, Meta
            byte[] lsb = new byte[4096];
            byte[] msb = new byte[2048];
            byte[] meta = new byte[2048];
            copyFromSlot(slot, "copyLSB", lsb);
            copyFromSlot(slot, "copyMSB", msb);
            copyFromSlot(slot, "copyBlockMetadata", meta);

            // Конвертируем в NEID 16-bit формат
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = y << 8 | z << 4 | x;
                        int lsbVal = lsb[index] & 0xFF;
                        int msbVal = get4bitsCoordinate(msb, x, y, z);
                        int metaVal = get4bitsCoordinate(meta, x, y, z);

                        int blockId = (msbVal << 8) | lsbVal;

                        // Записываем block ID (16-bit)
                        data[offset++] = (byte) ((blockId >> 8) & 0xFF);
                        data[offset++] = (byte) (blockId & 0xFF);
                    }
                }
            }

            // Аналогично для metadata (16-bit)
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int metaVal = get4bitsCoordinate(meta, x, y, z);
                        data[offset++] = 0;
                        data[offset++] = (byte) (metaVal & 0xFF);
                    }
                }
            }

            // BlockLight и SkyLight (копируем как есть)
            byte[] blockLight = new byte[2048];
            copyFromSlot(slot, "copyBlocklight", blockLight);
            System.arraycopy(blockLight, 0, data, offset, 2048);
            offset += 2048;

            if (!snapshot.isWorldHasNoSky()) {
                byte[] skyLight = new byte[2048];
                copyFromSlot(slot, "copySkylight", skyLight);
                System.arraycopy(skyLight, 0, data, offset, 2048);
                offset += 2048;
            }
        }

        // Копируем biome
        byte[] biomeArray = snapshot.getBiomeArray();
        System.arraycopy(biomeArray, 0, data, offset, 256);
        offset += 256;

        return data;
    }
}
```

**ПРЕИМУЩЕСТВА:**
1. Данные копируются НЕМЕДЛЕННО после создания пакета
2. MemSlot ещё гарантированно валиден (ChunkSnapshot ещё не освобождён)
3. После копирования пакет не зависит от ChunkSnapshot
4. ChunkSnapshot может быть безопасно освобождён в deflate()

### Решение #3: Исправить MixinEbsSaveFakeNbt

**Проблема:** `EbsSaveFakeNbt` читает из MemSlot асинхронно, но MemSlot может быть уже освобождён.

**ТЕКУЩИЙ КОД:**

```java
@Inject(method = "<init>", at = @At("RETURN"), require = 0, remap = false)
private void neid$forceConvertAfterInit(ExtendedBlockStorage ebs, boolean hasNoSky, CallbackInfo ci) {
    convertToNbt();  // Немедленная конвертация
}

@Overwrite(remap = false)
public void convertToNbt() {
    // Читает из MemSlot и создаёт NBT теги
    // ...
}
```

**ПРОБЛЕМА:** `convertToNbt()` создаёт NBT теги в heap, но Ultramine ожидает, что `write()` будет читать напрямую из MemSlot для zero-copy!

**РЕШЕНИЕ:** НЕ ИСПОЛЬЗОВАТЬ `convertToNbt()`! Вместо этого перехватить `write()` и записать данные немедленно:

```java
// MixinEbsSaveFakeNbt.java (ПЕРЕПИСАТЬ)
@Mixin(targets = "net.minecraft.nbt.EbsSaveFakeNbt", priority = 1500, remap = false)
public class MixinEbsSaveFakeNbt {

    @Shadow @Final private ExtendedBlockStorage ebs;
    @Shadow @Final private boolean hasNoSky;
    @Shadow private volatile boolean isNbt;

    /**
     * УДАЛИТЬ neid$forceConvertAfterInit!
     * Не нужно конвертировать в NBT заранее.
     */

    /**
     * OVERWRITE write() чтобы записать NEID 16-bit формат немедленно!
     */
    @Overwrite(remap = false)
    public void write(DataOutput out) throws IOException {
        LOGGER.info("EbsSaveFakeNbt.write() - writing NEID 16-bit format");

        // Записываем Y
        out.writeByte((byte)3);  // INT
        out.writeUTF("Y");
        out.writeInt(ebs.getYLocation() >> 4 & 255);

        // Получаем MemSlot
        Object slot = getSlotViaReflection();
        if (slot == null) {
            LOGGER.error("MemSlot is null!");
            out.writeByte(0);  // END tag
            return;
        }

        // КРИТИЧНО: Копируем данные ПРЯМО СЕЙЧАС, пока write() выполняется!
        byte[] lsb = new byte[4096];
        byte[] msb = new byte[2048];
        byte[] meta = new byte[2048];
        byte[] blockLight = new byte[2048];
        byte[] skyLight = hasNoSky ? null : new byte[2048];

        copyFromSlot(slot, "copyLSB", lsb);
        copyFromSlot(slot, "copyMSB", msb);
        copyFromSlot(slot, "copyBlockMetadata", meta);
        copyFromSlot(slot, "copyBlocklight", blockLight);
        if (!hasNoSky) {
            copyFromSlot(slot, "copySkylight", skyLight);
        }

        // Записываем vanilla формат (ТРЕБУЕТСЯ для загрузки!)
        writeByteArray(out, "Blocks", lsb);
        writeByteArray(out, "Add", msb);
        writeByteArray(out, "Data", meta);

        // Записываем NEID 16-bit формат
        byte[] blocks16 = convertTo16Bit(lsb, msb);
        byte[] data16 = convertMetaTo16Bit(meta);

        writeByteArray(out, "Blocks16", blocks16);
        writeByteArray(out, "Data16", data16);

        // Освещение
        writeByteArray(out, "BlockLight", blockLight);
        writeByteArray(out, "SkyLight", skyLight != null ? skyLight : new byte[2048]);

        out.writeByte(0);  // END tag

        LOGGER.info("EbsSaveFakeNbt.write() complete");
    }

    private static byte[] convertTo16Bit(byte[] lsb, byte[] msb) {
        byte[] result = new byte[4096 * 2];
        for (int i = 0; i < 4096; i++) {
            int y = (i >> 8) & 0xF;
            int z = (i >> 4) & 0xF;
            int x = i & 0xF;

            int lsbVal = lsb[i] & 0xFF;
            int msbVal = get4bitsCoordinate(msb, x, y, z);
            int blockId = (msbVal << 8) | lsbVal;

            result[i * 2] = (byte) (blockId & 0xFF);
            result[i * 2 + 1] = (byte) ((blockId >> 8) & 0xFF);
        }
        return result;
    }

    private static byte[] convertMetaTo16Bit(byte[] meta) {
        byte[] result = new byte[4096 * 2];
        for (int i = 0; i < 4096; i++) {
            int y = (i >> 8) & 0xF;
            int z = (i >> 4) & 0xF;
            int x = i & 0xF;

            int metaVal = get4bitsCoordinate(meta, x, y, z);
            result[i * 2] = (byte) (metaVal & 0xFF);
            result[i * 2 + 1] = 0;
        }
        return result;
    }
}
```

**ПРЕИМУЩЕСТВА:**
1. Данные копируются НЕМЕДЛЕННО в момент вызова `write()`
2. К этому моменту MemSlot ещё гарантированно валиден (т.к. EBS скопирован в `AnvilChunkLoader`)
3. Записываются и vanilla, и NEID форматы
4. Zero-copy всё ещё работает (копирование происходит только в `write()`, не в heap перед этим)

### Решение #4: Исправить загрузку из NBT

**Проблема:** При загрузке из NBT нужно загрузить NEID 16-bit формат обратно в MemSlot.

**РЕШЕНИЕ:**

```java
// MixinAnvilChunkLoaderUltramine.java (ДОБАВИТЬ)
@Inject(method = "readChunkFromNBT",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;<init>(IIZZ)V",
                 shift = At.Shift.AFTER),
        require = 0)
private void neid$loadNeidFormatAfterEbsCreation(World world, NBTTagCompound nbt, CallbackInfoReturnable<Chunk> cir,
                                                   @Local ExtendedBlockStorage ebs,
                                                   @Local NBTTagCompound ebsNbt) {
    // Проверяем наличие NEID формата
    if (!ebsNbt.hasKey("Blocks16", 7) || !ebsNbt.hasKey("Data16", 7)) {
        LOGGER.debug("No NEID format found, using vanilla");
        return;
    }

    LOGGER.info("Loading NEID 16-bit format into MemSlot");

    byte[] blocks16 = ebsNbt.getByteArray("Blocks16");
    byte[] data16 = ebsNbt.getByteArray("Data16");

    if (blocks16.length != 8192 || data16.length != 8192) {
        LOGGER.error("Invalid NEID format sizes: blocks16={}, data16={}",
                     blocks16.length, data16.length);
        return;
    }

    // Получаем MemSlot
    Object slot = getSlotViaReflection(ebs);
    if (slot == null) {
        LOGGER.error("MemSlot is null!");
        return;
    }

    // Конвертируем 16-bit → MemSlot формат (8-bit LSB + 4-bit MSB)
    byte[] lsb = new byte[4096];
    byte[] msb = new byte[2048];
    byte[] meta = new byte[2048];

    for (int i = 0; i < 4096; i++) {
        int y = (i >> 8) & 0xF;
        int z = (i >> 4) & 0xF;
        int x = i & 0xF;

        // Читаем 16-bit block ID (little-endian)
        int blockId = (blocks16[i * 2] & 0xFF) | ((blocks16[i * 2 + 1] & 0xFF) << 8);
        int metaVal = data16[i * 2] & 0xFF;

        // Записываем в vanilla формат
        lsb[i] = (byte) (blockId & 0xFF);
        set4bitsCoordinate(msb, x, y, z, (blockId >> 8) & 0xF);
        set4bitsCoordinate(meta, x, y, z, metaVal & 0xF);
    }

    // Загружаем в MemSlot
    setDataInSlot(slot, lsb, msb, meta,
                   ebsNbt.getByteArray("BlockLight"),
                   ebsNbt.hasKey("SkyLight", 7) ? ebsNbt.getByteArray("SkyLight") : null);

    LOGGER.info("NEID format loaded successfully");
}
```

---

## Детальный план исправления

### Этап 1: Исправить отправку пакетов (высокий приоритет)

**Файл:** `MixinS21PacketChunkDataUltramine.java`

**Изменения:**

1. **УДАЛИТЬ** текущий `@Overwrite func_149269_a()`
2. **СОЗДАТЬ** новый mixin для конструктора `S21PacketChunkData(ChunkSnapshot)`
3. **ДОБАВИТЬ** немедленное копирование данных из ChunkSnapshot

**Пример кода:**

```java
@Mixin(value = S21PacketChunkData.class, priority = 1600)
public class MixinS21PacketChunkDataUltramine {

    @Shadow private ChunkSnapshot chunkSnapshot;
    @Shadow private byte[] field_149278_f;
    @Shadow private int field_149283_c;
    @Shadow private int field_149280_d;
    @Shadow private boolean field_149279_g;

    /**
     * Перехватываем конструктор с ChunkSnapshot и НЕМЕДЛЕННО копируем данные!
     */
    @Inject(method = "<init>(Lorg/ultramine/server/chunk/ChunkSnapshot;)V",
            at = @At("RETURN"),
            require = 0,
            remap = false)
    private void neid$extractNeidDataImmediately(ChunkSnapshot snapshot, CallbackInfo ci) {
        LOGGER.info("Extracting NEID data from ChunkSnapshot for chunk ({},{})",
                    snapshot.getX(), snapshot.getZ());

        ExtendedBlockStorage[] ebsArray = snapshot.getEbsArr();

        // Вычисляем маску EBS
        int ebsMask = 0;
        for (int i = 0; i < ebsArray.length; i++) {
            ExtendedBlockStorage ebs = ebsArray[i];
            if (ebs != null) {
                try {
                    boolean isEmpty = (boolean) ebs.getClass().getMethod("isEmpty").invoke(ebs);
                    if (!isEmpty) {
                        ebsMask |= 1 << i;
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to check isEmpty", e);
                }
            }
        }

        this.field_149283_c = ebsMask;
        this.field_149280_d = ebsMask;

        // КРИТИЧНО: Копируем ВСЕ данные ПРЯМО СЕЙЧАС!
        this.field_149278_f = extractNeidDataFromSnapshot(snapshot, ebsArray, ebsMask);

        // Очищаем ссылку - она больше не нужна
        this.chunkSnapshot = null;

        LOGGER.info("NEID data extracted: size={} bytes, ebsMask=0x{}",
                    field_149278_f.length, Integer.toHexString(ebsMask));
    }

    private static byte[] extractNeidDataFromSnapshot(ChunkSnapshot snapshot,
                                                       ExtendedBlockStorage[] ebsArray,
                                                       int ebsMask) {
        // Полная реализация extractNeidDataFromSnapshot...
        // (см. Решение #2 выше)
    }
}
```

### Этап 2: Исправить сохранение (высокий приоритет)

**Файл:** `MixinEbsSaveFakeNbt.java`

**Изменения:**

1. **УДАЛИТЬ** `neid$forceConvertAfterInit()` inject
2. **ПЕРЕПИСАТЬ** `convertToNbt()` overwrite → `write()` overwrite
3. **ДОБАВИТЬ** немедленное копирование данных в `write()`

**Пример кода:**

```java
@Mixin(targets = "net.minecraft.nbt.EbsSaveFakeNbt", priority = 1500, remap = false)
public class MixinEbsSaveFakeNbt {

    @Shadow @Final private ExtendedBlockStorage ebs;
    @Shadow @Final private boolean hasNoSky;

    /**
     * OVERWRITE write() для записи NEID формата немедленно при вызове!
     */
    @Overwrite(remap = false)
    public void write(DataOutput out) throws IOException {
        LOGGER.info("EbsSaveFakeNbt.write() - writing NEID format");

        // Записываем compound header
        out.writeByte((byte)3);  // TAG_Int
        out.writeUTF("Y");
        out.writeInt(ebs.getYLocation() >> 4 & 255);

        // Получаем MemSlot и КОПИРУЕМ данные немедленно
        Object slot = getSlotViaReflection();
        if (slot == null) {
            LOGGER.error("MemSlot is null!");
            out.writeByte(0);
            return;
        }

        // Копируем ВСЕ данные СЕЙЧАС
        byte[] lsb = copyFromSlot(slot, "copyLSB");
        byte[] msb = copyFromSlot(slot, "copyMSB");
        byte[] meta = copyFromSlot(slot, "copyBlockMetadata");
        byte[] blockLight = copyFromSlot(slot, "copyBlocklight");
        byte[] skyLight = hasNoSky ? new byte[2048] : copyFromSlot(slot, "copySkylight");

        // Записываем vanilla формат (для совместимости)
        writeByteArray(out, "Blocks", lsb);
        writeByteArray(out, "Add", msb);
        writeByteArray(out, "Data", meta);

        // Записываем NEID 16-bit формат
        byte[] blocks16 = convertTo16BitBlocks(lsb, msb);
        byte[] data16 = convertTo16BitMeta(meta);
        writeByteArray(out, "Blocks16", blocks16);
        writeByteArray(out, "Data16", data16);

        // Освещение
        writeByteArray(out, "BlockLight", blockLight);
        writeByteArray(out, "SkyLight", skyLight);

        out.writeByte(0);  // END tag

        LOGGER.info("EbsSaveFakeNbt.write() complete");
    }

    // Helper methods...
}
```

### Этап 3: Исправить загрузку (средний приоритет)

**Файл:** `MixinAnvilChunkLoaderUltramine.java`

**Изменения:**

1. **ДОБАВИТЬ** inject для загрузки NEID формата из NBT
2. **КОНВЕРТИРОВАТЬ** 16-bit → MemSlot формат

**Пример кода:**

```java
@Inject(method = "readChunkFromNBT",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/world/chunk/storage/ExtendedBlockStorage;<init>(IIZZ)V",
                 shift = At.Shift.AFTER),
        require = 0)
private void neid$loadNeidFormat(World world, NBTTagCompound nbt,
                                 CallbackInfoReturnable<Chunk> cir,
                                 @Local ExtendedBlockStorage ebs,
                                 @Local NBTTagCompound ebsNbt) {
    if (!ebsNbt.hasKey("Blocks16", 7)) {
        return;  // Нет NEID формата
    }

    LOGGER.info("Loading NEID 16-bit format");

    byte[] blocks16 = ebsNbt.getByteArray("Blocks16");
    byte[] data16 = ebsNbt.getByteArray("Data16");

    // Конвертируем 16-bit → MemSlot формат
    byte[] lsb = new byte[4096];
    byte[] msb = new byte[2048];
    byte[] meta = new byte[2048];

    for (int i = 0; i < 4096; i++) {
        int blockId = (blocks16[i * 2] & 0xFF) | ((blocks16[i * 2 + 1] & 0xFF) << 8);
        int metaVal = data16[i * 2] & 0xFF;

        int y = (i >> 8) & 0xF;
        int z = (i >> 4) & 0xF;
        int x = i & 0xF;

        lsb[i] = (byte) (blockId & 0xFF);
        set4bitsCoordinate(msb, x, y, z, (blockId >> 8) & 0xF);
        set4bitsCoordinate(meta, x, y, z, metaVal);
    }

    // Загружаем в MemSlot
    Object slot = getSlotViaReflection(ebs);
    setDataInSlot(slot, lsb, msb, meta,
                  ebsNbt.getByteArray("BlockLight"),
                  ebsNbt.getByteArray("SkyLight"));

    LOGGER.info("NEID format loaded");
}
```

### Этап 4: Обновить синхронизацию (низкий приоритет)

**Файл:** `MixinExtendedBlockStorageUltramine.java`

**Изменения:**

1. **УДАЛИТЬ** `neid$syncBeforeCopy()` (больше не нужен)
2. **ОСТАВИТЬ** `neid$syncFromMemSlotAfterCopy()` (нужен для других сценариев)
3. **ОСТАВИТЬ** `neid$syncToMemSlotAfterSetBlock()` (нужен для изменений блоков)

**НО:** После Этапов 1-3 эта синхронизация должна работать корректно!

### Этап 5: Тестирование

**Тест 1: Отправка пакетов**
1. Запустить сервер
2. Подключиться клиентом
3. Проверить, что чанки отображаются корректно
4. ПКМ по блокам НЕ должен ничего менять

**Тест 2: Сохранение и загрузка**
1. Запустить сервер с новым миром
2. Изменить блоки
3. Сохранить и выключить сервер
4. Запустить снова
5. Проверить, что все блоки на месте

**Тест 3: Перезагрузка**
1. Запустить сервер
2. Подключиться клиентом
3. Прогрузить чанки
4. Перезагрузить сервер
5. Подключиться снова
6. Проверить, что чанки НЕ исчезли

---

## Резюме

### Корневые причины проблем:

1. **Race condition при отправке пакетов:** MemSlot освобождается до отправки пакета
2. **Неправильный источник данных:** MixinS21PacketChunkDataUltramine читает из оригинального chunk вместо ChunkSnapshot
3. **Асинхронное чтение из MemSlot:** EbsSaveFakeNbt читает из MemSlot через несколько тиков после копирования
4. **Отсутствие немедленного копирования:** Данные должны копироваться НЕМЕДЛЕННО, пока MemSlot валиден

### Решения:

1. **Перехватить конструктор S21PacketChunkData(ChunkSnapshot)** и немедленно скопировать данные
2. **Переписать EbsSaveFakeNbt.write()** для немедленного копирования данных при вызове
3. **Добавить загрузку NEID формата** в MixinAnvilChunkLoaderUltramine
4. **Сохранять оба формата** (vanilla + NEID) для совместимости

### Приоритеты:

1. **Высокий:** Исправить отправку пакетов (Этап 1)
2. **Высокий:** Исправить сохранение (Этап 2)
3. **Средний:** Исправить загрузку (Этап 3)
4. **Низкий:** Обновить синхронизацию (Этап 4)
5. **Критичный:** Тестирование (Этап 5)

---

## Дополнительные замечания

### Потенциальные проблемы:

1. **Производительность:** Немедленное копирование данных может увеличить CPU usage
   - **Решение:** Это компромисс между производительностью и корректностью. Ultramine уже использует zero-copy, но для NEID требуется конвертация формата.

2. **Память:** Двойное хранение (vanilla + NEID) увеличивает размер NBT
   - **Решение:** Увеличение незначительное (~8KB на EBS). Можно добавить конфиг опцию для хранения только NEID формата.

3. **Совместимость:** Vanilla клиенты не смогут подключаться
   - **Решение:** Это не проблема, т.к. NEID требует мод на клиенте.

### Рекомендации:

1. **Добавить debug логирование:** Логировать каждое копирование MemSlot для отладки
2. **Добавить проверки:** Проверять валидность данных перед записью/чтением
3. **Добавить метрики:** Считать количество race conditions и повторных копирований
4. **Документировать код:** Добавить комментарии о том, ПОЧЕМУ данные копируются немедленно

---

## Заключение

Проблемы NotEnoughIds с Ultramine Core master branch вызваны фундаментальным конфликтом между:
- **Ultramine:** Zero-copy, off-heap, delayed release
- **NEID:** Двойное хранение (MemSlot + массивы), синхронизация

Решение требует **немедленного копирования** данных из MemSlot в момент, когда они гарантированно валидны:
- При создании пакета → копируем в `S21PacketChunkData`
- При сохранении → копируем в `EbsSaveFakeNbt.write()`
- При загрузке → копируем из NBT в MemSlot

Это обеспечит корректность данных при любых условиях (race conditions, delayed release, async operations).
