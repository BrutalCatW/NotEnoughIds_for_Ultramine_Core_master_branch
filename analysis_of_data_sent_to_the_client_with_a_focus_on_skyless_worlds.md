📊 ПОЛНЫЙ АНАЛИЗ ОТПРАВКИ ДАННЫХ КЛИЕНТУ
🌍 МИРЫ БЕЗ НЕБА (hasNoSky = true)
Измерения с отключенным небом:
The End (WorldProviderEnd.java:21)

dimensionId = 1
hasNoSky = true
calculateCelestialAngle() возвращает 0.0F (нет цикла день/ночь)
canRespawnHere() возвращает false
isSkyColored() возвращает false
Nether (WorldProviderHell.java:19)

dimensionId = -1
hasNoSky = true
calculateCelestialAngle() возвращает 0.5F
canRespawnHere() возвращает false
WorldProviderEmpty (WorldProviderEmpty.java)

⚠️ НЕ устанавливает hasNoSky, значит по умолчанию hasNoSky = false
Это обычный мир с небом!
Использует ChunkProviderEmpty для генерации пустых чанков
В строке 41: вызывает generateSkylightMap() - генерирует освещение неба
📦 ОТПРАВКА ДАННЫХ ЧАНКОВ
S21PacketChunkData - Главный пакет отправки чанков
КРИТИЧЕСКАЯ ОСОБЕННОСТЬ для миров без неба:

// S21PacketChunkData.java:261-271
if (!p_149269_0_.worldObj.provider.hasNoSky)
{
    for (l = 0; l < aextendedblockstorage.length; ++l)
    {
        if (aextendedblockstorage[l] != null && ...) {
            aextendedblockstorage[l].getSlot().copySkylight(abyte, j);
            j += 2048;  // 2048 байт на секцию
        }
    }
}
Если hasNoSky = true → данные skylight НЕ отправляются!

Экономия: 2048 байт × количество секций на каждый чанк
Для полного чанка (16 секций): экономия ~32 KB на чанк
Структура данных в пакете чанка (порядок):
Block IDs (4096 байт на секцию) - строки 232-239
Block Metadata (2048 байт) - строки 243-250
Block Light (2048 байт) - строки 252-259
Sky Light (2048 байт) - строки 261-271 - ПРОПУСКАЕТСЯ если hasNoSky=true
Block MSB (2048 байт) - строки 273-283
Biome Array (256 байт, только для полных чанков) - строки 285-290
Компрессия данных:
Deflater с уровнем сжатия 7 (S21PacketChunkData.java:74)
Метод deflate() (строки 72-106)
Для ChunkSnapshot: специальная оптимизация через UMHooks.extractAndDeflateChunkPacketData() (строка 79)
🚀 СИСТЕМА УПРАВЛЕНИЯ ОТПРАВКОЙ ЧАНКОВ
ChunkSendManager - Асинхронная отправка с динамической скоростью
Динамическая регулировка скорости (строки 206-265):
MIN_RATE = 0.2 чанка/тик
MAX_RATE = конфигурируемое значение (maxSendRate)

Алгоритм адаптации:
- Если очередь пуста → rate += 0.14
- Если очередь < maxRate → rate += 0.07
- Если очередь растет → rate -= 0.07 или 0.14
Процесс отправки чанка:
Сортировка очереди (строки 80-93)

По направлению взгляда игрока (BlockFace.yawToFace())
По дистанции от игрока
Асинхронная загрузка (строка 325)

loadAsyncWithRadius() с радиусом 1
Создание снимка (строка 470)

ChunkSnapshot.of(chunk) - синхронно!
Async обработка (строки 486-497):

Anti-XRay обработка
Создание пакета
Компрессия (deflate)
Отправка (строка 505)

scheduleOutboundPacket() через NetworkManager
С callback для декремента счетчика очереди
🎮 ИНИЦИАЛИЗАЦИЯ ИГРОКА И ОТПРАВКА ДАННЫХ О МИРЕ
Последовательность при входе (ServerConfigurationManager.java:125-215):
1. Строка 158: S01PacketJoinGame
   └─ Entity ID, Game Type, Hardcore, Dimension ID, Difficulty, Max Players, World Type
   └─ ВАЖНО: Для длинных dimension ID (>127) отправляется 0 вместо реального ID

2. Строка 160: S07PacketRespawn (только если dimension ID > 127)
   └─ Реальный Dimension ID, Difficulty, World Type, Game Type

3. Строка 162: S05PacketSpawnPosition
   └─ X, Y, Z координаты спавна

4. Строка 163: S39PacketPlayerAbilities
   └─ Способности игрока

5. Строка 164: S09PacketHeldItemChange
   └─ Выбранный слот инвентаря

6. Строка 185: updateTimeAndWeatherForPlayer()
   └─ S03PacketTimeUpdate + S2BPacketChangeGameState
updateTimeAndWeatherForPlayer (строки 996-1006):
// S03PacketTimeUpdate
p_72354_1_.playerNetServerHandler.sendPacket(
    new S03PacketTimeUpdate(
        totalWorldTime,
        worldTime,
        doDaylightCycle  // если false, время отрицательное
    )
);

// Если идет дождь:
if (p_72354_2_.isRaining()) {
    // State 1: Начало дождя
    sendPacket(new S2BPacketChangeGameState(1, 0.0F));
    
    // State 7: Сила дождя (0.0 - 1.0)
    sendPacket(new S2BPacketChangeGameState(7, rainStrength));
    
    // State 8: Сила грозы (0.0 - 1.0)
    sendPacket(new S2BPacketChangeGameState(8, thunderStrength));
}
⚠️ ВАЖНО: Пакеты времени и погоды отправляются для ВСЕХ измерений, но:

В The End/Nether клиент игнорирует время (нет визуального цикла)
В мирах без неба дождь не отображается
🔄 РЕСПАВН И СМЕНА ИЗМЕРЕНИЯ
respawnPlayer (строки 455-530):
1. Проверка существования мира (строки 457-465)
   └─ Если мир не существует → dimension = 0
   └─ Если canRespawnHere() == false → вызывается getRespawnDimension()
      (The End возвращает 0, т.е. Overworld)

2. Создание нового EntityPlayerMP (строка 485)

3. Строка 517: S07PacketRespawn
   └─ Новое измерение, difficulty, world type, game type

4. Строка 520: S05PacketSpawnPosition
   └─ Новые координаты спавна

5. Строка 522: updateTimeAndWeatherForPlayer()
   └─ Время и погода для нового измерения
transferPlayerToDimension (строки 537-568):
1. Строка 550: S07PacketRespawn
   └─ С новым dimension ID

2. Строка 555: transferEntityToWorld()
   └─ Физическое перемещение через Teleporter

3. Строка 558: updateTimeAndWeatherForPlayer()
   └─ Обновление времени/погоды для нового измерения

4. Строка 559: syncPlayerInventory()
   └─ Синхронизация инвентаря

5. Строки 560-566: Отправка активных эффектов
   └─ S1DPacketEntityEffect для каждого эффекта
📡 ОСНОВНЫЕ ПАКЕТЫ ОТПРАВКИ ДАННЫХ
1. S01PacketJoinGame - Вход в игру
- Entity ID (int)
- Game Type (byte) - с флагом hardcore (бит 3)
- Dimension ID (byte) - или 0 для длинных ID
- Difficulty (byte)
- Max Players (byte)
- World Type (String)
2. S07PacketRespawn - Респавн/Смена измерения
- Dimension ID (int) - полный int для поддержки длинных ID
- Difficulty (byte)
- Game Type (byte)
- World Type (String)
3. S21PacketChunkData - Данные чанка
- Chunk X (int)
- Chunk Z (int)
- Full Chunk (boolean)
- Primary Bit Mask (short) - какие секции отправляются
- Add Bit Mask (short) - MSB данные
- Compressed Size (int)
- Compressed Data (byte[])
4. S03PacketTimeUpdate - Время
- Total World Time (long)
- World Time (long) - отрицательное если doDaylightCycle=false
5. S2BPacketChangeGameState - Состояние игры
State IDs:
- 0: Invalid bed
- 1: Begin raining
- 2: End raining
- 3: Change game mode
- 7: Rain strength (0.0-1.0)
- 8: Thunder strength (0.0-1.0)
🔧 СПЕЦИФИКА РАЗНЫХ ТИПОВ МИРОВ
The End (hasNoSky = true, dimensionId = 1)
✅ Отправляется:

Block data, metadata, block light
Block MSB, biomes
Время (но не используется визуально)
Погода (но не отображается)
❌ НЕ отправляется:

Sky light данные (экономия ~32KB на чанк)
Nether (hasNoSky = true, dimensionId = -1)
Аналогично The End

Overworld (hasNoSky = false, dimensionId = 0)
✅ Отправляется ВСЁ:

Все данные блоков
Sky light
Время с визуализацией
Погода с визуализацией
WorldProviderEmpty (hasNoSky = false!)
✅ Отправляется:

Всё как в Overworld (включая sky light!)
Вызывает generateSkylightMap() (строка 41)
🎯 КЛЮЧЕВЫЕ ФАЙЛЫ С НОМЕРАМИ СТРОК
Провайдеры миров:
WorldProvider.java:34 - поле hasNoSky
WorldProvider.java:499 - getActualHeight() возвращает 128 для hasNoSky, иначе 256
WorldProviderEnd.java:21 - установка hasNoSky = true
WorldProviderHell.java:19 - установка hasNoSky = true
WorldProviderEmpty.java - НЕ устанавливает hasNoSky
Отправка чанков:
S21PacketChunkData.java:261-271 - КРИТИЧЕСКАЯ проверка hasNoSky для skylight
S21PacketChunkData.java:72-106 - метод deflate()
S21PacketChunkData.java:345-347 - фабричные методы makeForSend()
ChunkSendManager.java:206-265 - динамическая регулировка скорости
ChunkSendManager.java:461-519 - CompressAndSendChunkTask
Инициализация игрока:
ServerConfigurationManager.java:158 - S01PacketJoinGame
ServerConfigurationManager.java:160 - S07PacketRespawn для длинных dimension ID
ServerConfigurationManager.java:996-1006 - updateTimeAndWeatherForPlayer()
ServerConfigurationManager.java:1074-1086 - func_72381_a() (установка game type)
Респавн и смена измерения:
ServerConfigurationManager.java:455-530 - respawnPlayer()
ServerConfigurationManager.java:517 - отправка S07PacketRespawn при респавне
ServerConfigurationManager.java:537-568 - transferPlayerToDimension()
ServerConfigurationManager.java:550 - отправка S07PacketRespawn при смене измерения
Пакеты:
S01PacketJoinGame.java:27-36 - конструктор
S07PacketRespawn.java:24-30 - конструктор
S03PacketTimeUpdate.java:19-33 - конструктор с обработкой отрицательного времени
S2BPacketChangeGameState.java:20-24 - конструктор состояния игры
💡 ВАЖНЫЕ ВЫВОДЫ
Оптимизация для миров без неба:

The End и Nether экономят ~32KB на чанк за счет отсутствия skylight
Для 100 чанков это ~3.2MB экономии памяти и трафика
WorldProviderEmpty не является "миром без неба":

Несмотря на название, он генерирует skylight
Для создания настоящего мира без неба нужно установить hasNoSky = true в registerWorldChunkManager()
Система отправки чанков высоко оптимизирована:

Асинхронная обработка
Динамическая регулировка скорости
Приоритизация по направлению взгляда
Компрессия уровня 7
Универсальность пакетов времени/погоды:

Отправляются для всех измерений
Клиент самостоятельно решает, отображать или нет
В The End/Nether игнорируются визуально