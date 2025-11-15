ПОЛНЫЙ АНАЛИЗ DEFLATE() В ПРОЕКТЕ
Общее описание
В проекте используется java.util.zip.Deflater - стандартная Java библиотека для сжатия данных по алгоритму DEFLATE (тот же алгоритм, что используется в ZIP, GZIP). Сжатие применяется для:

Передачи чанков по сети (от сервера к клиенту)
Сохранения чанков на диск (region файлы)
1. S21PacketChunkData.java - Пакет отправки одного чанка
Расположение
src/main/java/net/minecraft/network/play/server/S21PacketChunkData.java

Основные моменты
Импорты:

java.util.zip.Deflater (строка 10)
java.util.zip.Inflater (строка 11) - для распаковки на клиенте
Поля класса:

private Semaphore deflateGate; // строка 35 - семафор для синхронизации сжатия
Метод deflate() (строки 72-106):

public void deflate()
{
    Deflater deflater = new Deflater(7); // Уровень сжатия 7 (средний)
    try {
        if(chunkSnapshot != null) {
            // Использует UMHooks для сжатия snapshot'а
            UMHooks.ChunkPacketData data = UMHooks.extractAndDeflateChunkPacketData(deflater, chunkSnapshot);
            field_149281_e = data.data;
            field_149285_h = data.length;
            field_149280_d = data.ebsMask;
            field_149283_c = data.ebsMask;
            field_149278_f = null;
            chunkSnapshot.release(); // ВАЖНО: освобождает snapshot
            chunkSnapshot = null;
            return;
        }
        // Для обычных чанков (без snapshot)
        deflater.setInput(this.field_149278_f, 0, this.field_149278_f.length);
        deflater.finish();
        byte[] deflated = new byte[4096];
        int dataLen = 0;
        while (!deflater.finished()) {
            if(dataLen == deflated.length)
                deflated = Arrays.copyOf(deflated, deflated.length * 2); // Динамическое расширение буфера
            dataLen += deflater.deflate(deflated, dataLen, deflated.length - dataLen);
        }
        this.field_149285_h = dataLen;
        this.field_149281_e = deflated;
    } finally {
        deflater.end(); // ВАЖНО: освобождает нативные ресурсы deflater
    }
}
Вызов deflate() (строки 164-174):

public void writePacketData(PacketBuffer p_148840_1_) throws IOException
{
    if (this.field_149281_e == null) {
        deflateGate.acquireUninterruptibly(); // Захват семафора
        if (this.field_149281_e == null) { // Double-checked locking
            deflate();
        }
        deflateGate.release();
    }
    // ... запись данных в буфер
}
Особенности:

Использует семафор для предотвращения многократного сжатия
Double-checked locking для оптимизации
Динамическое расширение буфера (удваивание размера)
Уровень сжатия 7 (баланс между скоростью и размером)
2. S26PacketMapChunkBulk.java - Пакет отправки нескольких чанков
Расположение
src/main/java/net/minecraft/network/play/server/S26PacketMapChunkBulk.java

Основные моменты
Импорты:

java.util.zip.Deflater (строка 9)
Поля:

private Semaphore deflateGate; // строка 30
Метод deflate() (строки 60-83):

private void deflate()
{
    // Объединение всех чанков в один массив
    byte[] data = new byte[maxLen];
    int offset = 0;
    for (int x = 0; x < field_149260_f.length; x++) {
        System.arraycopy(field_149260_f[x], 0, data, offset, field_149260_f[x].length);
        offset += field_149260_f[x].length;
    }
    
    Deflater deflater = new Deflater(-1); // Уровень -1 = DEFAULT_COMPRESSION (6)
    
    try {
        deflater.setInput(data, 0, data.length);
        deflater.finish();
        byte[] deflated = new byte[data.length]; // Буфер = размер входных данных
        this.field_149261_g = deflater.deflate(deflated); // Одиночный вызов deflate
        this.field_149263_e = deflated;
    } finally {
        deflater.end();
    }
}
Вызов deflate() (строки 156-166):

public void writePacketData(PacketBuffer p_148840_1_) throws IOException
{
    if (this.field_149263_e == null) {
        deflateGate.acquireUninterruptibly();
        if (this.field_149263_e == null) {
            deflate();
        }
        deflateGate.release();
    }
    // ... запись данных
}
Фабричный метод (строки 245-250):

public static S26PacketMapChunkBulk makeDeflated(List chunks)
{
    S26PacketMapChunkBulk pkt = new S26PacketMapChunkBulk(chunks);
    pkt.deflate(); // Предварительное сжатие
    return pkt;
}
Особенности:

Уровень сжатия -1 (DEFAULT = 6, ниже чем у S21PacketChunkData)
Объединяет все чанки в один массив перед сжатием
Одиночный вызов deflater.deflate() (не в цикле)
Фиксированный буфер размером с входные данные
3. RegionFile.java - Сохранение чанков на диск
Расположение
src/main/java/net/minecraft/world/chunk/storage/RegionFile.java

Основные моменты
Импорты:

java.util.zip.DeflaterOutputStream (строка 12)
Использование (строка 215):

public DataOutputStream getChunkDataOutputStream(int p_76710_1_, int p_76710_2_)
{
    return this.outOfBounds(p_76710_1_, p_76710_2_) ? null : 
        new DataOutputStream(
            new DeflaterOutputStream(
                new RegionFile.ChunkBuffer(p_76710_1_, p_76710_2_)
            )
        );
}
Особенности:

Использует DeflaterOutputStream (высокоуровневая обертка)
Сжимает данные при записи на диск
Автоматическое управление Deflater (не нужно вызывать end())
Запись с версией 2 (строка 318) - формат Deflate
4. ChunkSendManager.java - Управление отправкой чанков
Расположение
src/main/java/org/ultramine/server/chunk/ChunkSendManager.java

Основные моменты
Вызов deflate() (строка 497):

private class CompressAndSendChunkTask implements Runnable
{
    @Override
    public void run()
    {
        // ...
        antiXRayService.prepareChunkAsync(chunkSnapshot, antiXRayParam);
        S21PacketChunkData packet = S21PacketChunkData.makeForSend(chunkSnapshot);
        packet.deflate(); // Сжатие в отдельном потоке!!!
        
        // Синхронизированная отправка
        synchronized(lock) {
            if(!checkActual())
                return;
            player.playerNetServerHandler.netManager.scheduleOutboundPacket(packet, ...);
            sendingStage2.add(...);
        }
        
        toUpdate.add(chunkId);
    }
}
Особенности:

Сжатие выполняется в executor (отдельном потоке) (строка 445)
Асинхронная обработка - не блокирует основной поток сервера
Anti-XRay применяется ДО сжатия
Освобождение chunkSnapshot происходит внутри deflate()
Executor:

private static final ExecutorService executor = Executors.newFixedThreadPool(1); // строка 46
5. UMHooks.java - Низкоуровневое сжатие чанков
Расположение
src/main/java/org/ultramine/server/internal/UMHooks.java

Основные моменты
Импорты:

java.util.zip.Deflater (строка 12)
Публичный метод (строки 283-286):

public static ChunkPacketData extractAndDeflateChunkPacketData(Deflater deflater, ChunkSnapshot chunkSnapshot)
{
    return new ChunkPacker(deflater, chunkSnapshot).pack();
}
Класс ChunkPacker (строки 288-399):

Константы:

private static final ThreadLocal<byte[]> LOCAL_BUFFER = ThreadLocal.withInitial(LambdaHolder.newByteArray(4096));
private static final byte[] EMPTY_CHUNK_SEQUENCE = {120, -38, -19, -63, 49, ...}; // Предсжатая пустая секция
Метод pack() (строки 303-383):

Проверяет маску секций (ExtendedBlockStorage)
Для пустого чанка возвращает предсжатую константу (оптимизация!)
Копирует данные блоков в порядке:
LSB (младшие байты ID блоков) - 4096 байт на секцию
Metadata - 2048 байт на секцию
Blocklight - 2048 байт на секцию
Skylight (если есть небо) - 2048 байт на секцию
MSB (старшие байты ID блоков) - 2048 байт на секцию
Biome array - 256 байт
Метод write() (строки 385-391):

private void write(byte[] src, int srcLen)
{
    deflater.setInput(src, 0, srcLen);
    while (!deflater.needsInput()) { // Цикл до полной обработки
        deflate();
    }
}
Метод deflate() (строки 393-398):

private void deflate()
{
    if(dataLen == data.length)
        data = Arrays.copyOf(data, data.length * 2); // Динамическое расширение x2
    dataLen += deflater.deflate(data, dataLen, data.length - dataLen);
}
Финализация (строки 377-380):

deflater.finish();
while (!deflater.finished()) {
    deflate();
}
Особенности:

ThreadLocal буфер для избежания аллокаций
Предсжатая константа для пустых чанков
Поэтапное сжатие - данные подаются блоками
Динамическое расширение выходного буфера
Deflater передается извне (можно переиспользовать)
СВОДНАЯ ТАБЛИЦА ИСПОЛЬЗОВАНИЯ
| Файл | Уровень сжатия | Режим | Поток | Назначение | |------|----------------|-------|--------|------------| | S21PacketChunkData | 7 (средний) | Синхронный с семафором | Executor thread | Один чанк → клиент | | S26PacketMapChunkBulk | -1 (default=6) | Синхронный с семафором | Executor thread | Много чанков → клиент | | RegionFile | Default | Stream | Main thread | Чанк → диск | | UMHooks.ChunkPacker | Внешний deflater | Управляемый вручную | Executor thread | Низкоуровневая упаковка |

АРХИТЕКТУРА СЖАТИЯ ЧАНКОВ
Поток данных при отправке чанка игроку:
1. ChunkSendManager.sendChunks()
   └─> Загрузка чанка (async)
       └─> ChunkLoadCallback.onChunkLoaded()
           └─> Создание ChunkSnapshot (sync)
               └─> Executor.execute(CompressAndSendChunkTask)
                   ├─> Anti-XRay обработка (async)
                   ├─> S21PacketChunkData.makeForSend(chunkSnapshot)
                   ├─> packet.deflate()
                   │   └─> UMHooks.extractAndDeflateChunkPacketData()
                   │       └─> ChunkPacker.pack()
                   │           ├─> Копирование LSB, Metadata, Light, MSB, Biome
                   │           ├─> Deflater.setInput() x N
                   │           ├─> Deflater.deflate() x N (динамический буфер)
                   │           └─> Deflater.finish()
                   └─> scheduleOutboundPacket(packet)
КЛЮЧЕВЫЕ ОПТИМИЗАЦИИ
1. Асинхронное сжатие
Выполняется в отдельном пуле потоков (строка 46 ChunkSendManager)
Не блокирует основной поток сервера
2. Double-checked locking
В writePacketData() обоих пакетных классов
Гарантирует однократное сжатие при многопоточности
3. ThreadLocal буфер
В UMHooks.ChunkPacker (строка 290)
Избегает аллокаций при каждом сжатии
4. Предсжатая константа
EMPTY_CHUNK_SEQUENCE (строка 291)
Для пустых чанков - мгновенная обработка
5. Динамическое расширение буфера
В S21PacketChunkData и UMHooks.ChunkPacker
Удваивание размера при нехватке места
6. Семафоры
deflateGate в обоих пакетных классах
Контроль конкурентного доступа
7. ChunkSnapshot
Неизменяемая копия чанка
Позволяет безопасно обрабатывать в другом потоке
Освобождается после сжатия (release())
УРОВНИ СЖАТИЯ
S21PacketChunkData: 7 - хороший баланс для одиночных чанков
S26PacketMapChunkBulk: -1 (6) - чуть быстрее для bulk операций
RegionFile: default - используется DeflaterOutputStream
Более высокий уровень в S21 обоснован тем, что это основной метод отправки, и лучшее сжатие экономит трафик.

ПОТЕНЦИАЛЬНЫЕ ПРОБЛЕМЫ
1. Утечка ресурсов
✅ Везде используется try-finally с deflater.end()
✅ DeflaterOutputStream автоматически управляет ресурсами
2. Многопоточность
✅ Семафоры защищают от повторного сжатия
✅ Deflater не разделяется между потоками
✅ ThreadLocal буфер для каждого потока
3. Производительность
✅ Асинхронное выполнение
✅ Adaptive rate control в ChunkSendManager
⚠️ Executor с 1 потоком (строка 46) - может быть узким местом
4. Память
✅ Динамическое расширение буферов
✅ ChunkSnapshot освобождается после сжатия
⚠️ Копирование данных при расширении массива
ОСОБЕННОСТИ РЕАЛИЗАЦИИ
Формат сжатых данных чанка:
16 секций (Y-слои) по 16x16x16 блоков
Для каждой непустой секции:
LSB блоков (4096 байт)
Metadata (2048 байт)
Block light (2048 байт)
Sky light (2048 байт, если есть)
MSB блоков (2048 байт)
Биомы (256 байт)
Все это сжимается Deflater в один blob.

ВЗАИМОДЕЙСТВИЕ С ANTI-XRAY
antiXRayService.prepareChunkAsync(chunkSnapshot, antiXRayParam); // строка 495
S21PacketChunkData packet = S21PacketChunkData.makeForSend(chunkSnapshot);
packet.deflate(); // Anti-XRay уже применен!
Anti-XRay модифицирует данные в ChunkSnapshot до сжатия, что важно для корректности.

ВЫВОДЫ
Deflate используется для сжатия данных чанков при передаче по сети и сохранении на диск
Два основных пакета: S21 (один чанк) и S26 (bulk)
Асинхронная архитектура - сжатие в отдельных потоках
Множество оптимизаций: ThreadLocal буферы, предсжатые константы, dynamic buffers
Правильное управление ресурсами: try-finally, deflater.end()
Thread-safe: семафоры, отдельные Deflater на операцию
Уровни сжатия подобраны под задачу (7 для single, 6 для bulk)
Реализация выглядит зрелой и оптимизированной, с учетом многопоточности и производительности.