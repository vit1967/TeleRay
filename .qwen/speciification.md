###Current ToDo:
покажи все куски кода(в консоль), где :
    1. определяется миним.время между чтениями MTProxy из канала MTProxy после запуска startLoopTstConnect() или startMtproxyLoop() и макс. кол-во чтений MTProxy за 1 цикл
    2. где TeleRay тестирует прямое соединение к серверам Telegram (по времени это происходит сразу после нажатия кн. v2raySendKeyRow "Turn Auto upd. v2ray & MTP" )

3. ничегоне меняй.












##old ToDo orders 5 ( выполнено полностью ) ##
 Переделай панель ConfigSubscrBotRequest.java так:
1. В Request section, вместо 4ех однотипных 2ух строчных позиций
        // Request section
        request1Row = rowCount++;
        updateKey1Row = rowCount++;

        request2Row = rowCount++;
        updateKey2Row = rowCount++;

        request3Row = rowCount++;
        updateKey3Row = rowCount++;

        request4Row = rowCount++;
        updateKey4Row = rowCount++;
сделай массив однотипных и одинаково функциональных "позиций" до макс. 14ти элементов.
2. Сначала (по умолчанию) видна 1на 1вая позиция 
        requestRow[0] = rowCount++;
        updateKeyRow[0] = rowCount++;
    которая выполняет полностью весь функционал старой позиции 
        request1Row = rowCount++;
        updateKey1Row = rowCount++;
    , включая (просто напоминаю на всякий, т.к. функционал этих старых полей
        request1Row = rowCount++;
        updateKey1Row = rowCount++;

        request2Row = rowCount++;
        updateKey2Row = rowCount++;

        request3Row = rowCount++;
        updateKey3Row = rowCount++;

        request4Row = rowCount++;
        updateKey4Row = rowCount++;
    реализован хорошо, и не хотелось бы , чтобы он изменился при переходе на 
        requestRow[0] = rowCount++;
        updateKeyRow[0] = rowCount++; и.т.д.
        то есть (примерно напоминаю): 
        2.1. При тапе ( коротком ) на поле request1Row "Запрос 1" :
          Если поле requestRow[N] чем-то заполнено, и в конце заполняющей строки  нет символа ";"(точка с запятой) то поле доступно только в режиме обычного ручного текстового редактирования пользователем.
         2.2. Если поле requestRow[N] пусто, или  заполнено, но в конце заполняющей строки - символ ";"(точка с запятой)  то  должно открывается окно ChatActivity переписки с ботом . Имя бота для ВСЕХ позиций берется  из ранее заполненного поля `subscription1Row`(выбором чата DialogsActivity). В subscription1Row ничего менять не надо (получается)!
         2.3.  При возврате из ChatActivity  происходит автозаполнение поля requestRow[N]  из буфера обмена (всеми собранными в 1ну строку текстами, выбранным в окне ChatActivity переписки с ботом  ) НО если поле requestRow[N] было не пусто, а заполнено, но в конце заполняющей строки - символ ";"(точка с запятой) , то из буфера производится дописывание после последнего символа ";"(точка с запятой), и добавленного за ним " "(пробела)
    3. после всех, уже заполненных (ранее) и видимых позиций , ниже должна постоянно (до полного заполнения массива 14ю значениями) 
    находится кнопка "+ Add Subscr.Bot command"  подобно тому, как  в  TMessagesProj/src/main/java/org/telegram/ui/FiltersSetupActivity.java  при тапе - наажатии на "+ Create New Folder" (создания новой папки) открывается  файл FilterCreateActivity.java   из FiltersSetupActivity.java,
 и потом добавленные папки появляются внизу этой панели "отодвигая" кнопку "+ Create New Folder" еще ниже (возьми как образец).
    4.1. Если бот отдал  Individual keys , то он ззаписывается в requestRow[N]      Each field has separate SharedConfig key - easier compatibility                                                                        │
    4.2. Если бот отдал   JSON array , то он проходит дополнительную проверку(типовую на выполнение всех стандартов для экспортируемых в приложенияя типа v2ray JSON array) , и потом запись строки в requestRow[N] , где ессно видно будет только ее начало (поле то все равно не текстово-редактируемо вручную;)
    4.3. Если бот не отдал свежих ключей (только сообщение с "мусором"), то в поле requestRow[N] остается старый ключ (не меняется), по выводится красный информирующий Toast , "Свежего кл. нет. оставляю старый"


В остальном функциональность ConfigSubscrBotRequest  (самое главное нормально работающая сейчас передача ключей-профилей to app. v2rayTg in module v2ray при тапе на заполненные updateKeyRow[N] ) ДОЛЖНА СОХРАНИТЬСЯ!

При ЛЮБЫХ непонятках, сомнениях и отходе от задач speciification.md сначала останавливайся и уточняй - задавай уточняющие вопросы!

fix build errors














##old ToDo orders 4 ( выполнено полностью ) ##
В  ConfigSubscrBotRequest.java. :
    После каждого из
        request1Row 
        request11Row 
        request12Row 
        request13Row 
    вставь соответствующие ему поля 
        keyHeaderRow 
        updateKey1Row 
    , НО сделай их аналогично, как сделаны hint и поле subscription1Row в в V2rayConfigFragment, то есть clicable hint-заголовок слева,         а само редактируемое поле с возвращаемым выбранным значениеп после его заполнения позицианируется справа, не перекрывая hint-заголовок  (м.б. выровнено по правому краю? )
2. во все соответствующие поля updateKey*Row  возвращаются полученные при нажатии соответствующих  keyHeader*Row  , от соответствующих команд боту,  ключи-профили  аналогично как ранее в 1но поле keyHeaderRow  возвращался ключь-профиль   при выборе команды из 1го поля request1Row
    


Составь план и добавь его в конец @QWEN.md







##old ToDo orders 3 ( выполнено полностью ) ##
1. В V2rayConfigFragment.java : При тапе на subscription1Row всегда открывается ConfigSubscrBotRequest.java.
2. Если в V2rayConfigFragmentпри при тапе  на subscription1Row оно было пусто, то сразу после открытия ConfigSubscrBotRequest
    открывается ChatActivity для выбора чата с ботом  
    (ранее это происходилов в V2rayConfigFragment и было реализовано примерно так:
            } else if (position == subscription1Row ) {
                wasDialogSelected = false;
                choosingForSubscription = position == subscription1Row ? 1 : 2;
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
                DialogsActivity dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setDelegate(V2rayConfigFragment.this);
                presentFragment(dialogsActivity);
            }
    -реализуй аналогичное поведение теперь в ConfigSubscrBotRequest. Т.е. 
    Если request1Row пустое → открыть окно выбора чата (DialogsActivity) , из которого затем вернуть Username бота выбранного чата 
    и записать  в request1Row (с "@" в 1-вом символе )
  - Если выбор из DialogsActivity пустой (польз-ль нажал стрелку "назад" не выбрав чат), то поле subscription1Row очищается 
        ( и возм. Обновляет UI через listAdapter.notifyDataSetChanged() )




##old ToDo orders 2 ( выполнено  ) ##
    Краткое содержание плана:

    Проблема
     - Дублирование строк "Подписка 2" (видно на фото)
     - Буфер обмена не записывается в request1Row после возврата из ChatActivity
     - Конфликт переменных choosingForSubscription и choosingForRequestField

    Решение
     1. Удалить Подписку 2 из V2rayConfigFragment
     2. Создать новый фрагмент ConfigSubscrBotRequest.java для настройки Подписки 1
     3. Оставить одно кликабельное поле "Подписка 1" в главном окне

    Архитектура

     1 V2rayConfigFragment (главный экран)
     2   └─→ Подписка 1 (тап) → ConfigSubscrBotRequest
     3        └─→ subscription (тап) → DialogsActivity (выбор бота)
     4        └─→ request1/11/12/13 (тап) → ChatActivity (копирование команды)
     5        └─→ updateKey1 (тап) → отправка сообщения боту

    7 шагов реализации:
     1. Создать ConfigSubscrBotRequest.java
     2. Модифицировать V2rayConfigFragment.java (удалить subscription 2 со всеми связанными полями  request21Row,    request22Row,        request23Row ,        updateKey2Row ,        key2Row ) 
     3. В ConfigSubscrBotRequest особое внимание обратить на логику автозаполнения из буфера после вызова ChatActivity из полей 
    request1Row ,
        request11Row 
        request12Row 
        request13Row 
ЭТИ Поля в ConfigSubscrBotRequest должны работать так:
 При тапе ( коротком ) на поле request1Row "Запрос 1" :
 1.1. Если поле request1Row чем-то заполнено, то поле доступно  в режим текстового редактирования поля request1Row вручную пользователем.
 1.2. Если поле request1Row пусто, то  должно открывается окно ChatActivity переписки с ботом (А НЕ окно выбора чата DialogsActivity) . Имя бота беретс  из ранее заполненного поля `subscription1Row`.

-1.3.  При возврате из ChatActivity  происходит автозаполнение поля request1Row  из буфера обмена (выбранным в окне ChatActivity переписки с ботом  ) 
1.4. Если стереть вручную путем редактирования поле request1Row , затем выйти из панели настроек ,и потом зайти обратно , то поскольку поле request1Row пусто, то при тапе на него должно открывается окно ChatActivity переписки с ботом (по п. 1.2.)   

1.5. При тапе на уже ранее заполненное поле request1Row ничего открывать не надо, а пользователю должны быть доступны все стандартные ф-ции редактирования текстовых редактируемых полей (с стандартным всплывающим при долгом тапе мень "выделить, выбрать все, удалить ")
1.6. редактируемое поле request1Row должно находиться в 1й строке (на 1м вертикальном уровне ) с заголовком "Запрос 1", но правее его (не загораживая заголовок собой)


     4. Исправить Focus Change Listener
     5. Обновить SharedConfig.java
     6. Обновить strings.xml
     7. Исправить адаптер

    Критерии успеха:
    ✅ Нет дублирования строк
    ✅ Буфер обмена работает корректно
    ✅ Все поля запросов работают независимо
    ✅ Сборка проходит без ошибок



##old ToDo orders 1  ( выполнено  )  ##
размножь request1Row в поля 
 request11Row ,  
 request12Row , 
 request13Row ,  
 
При исп- нии всех этих запросов полученный от бота ключ попадает в 1 старое поле key1Row
, и после поля subscription2Row аналогично поля , аналогичные старому request2Row
 request21Row ,  
 request22Row ,  
 request23Row ,  
При исп- нии всех этих запросов полученный от бота ключ попадает в 1 старое поле key2Row




##old ToDo orders 0  ( выполнено  )  ##

В панели настроек-тестов взаимодействия с V2ray TMessagesProj/src/main/java/org/telegram/ui/V2rayConfigFragment.java с редактируемым полем  "Запрос 1" request1Row должно происходить следующее: 

1. При тапе ( коротком ) на поле request1Row "Запрос 1" :
 1.1. Если поле request1Row чем-то заполнено, то поле доступно  в режим текстового редактирования поля request1Row вручную пользователем.
 1.2. Если поле request1Row пусто, то  должно открывается окно ChatActivity переписки с ботом (А НЕ окно выбора чата DialogsActivity) . Имя бота беретс  из ранее заполненного поля `subscription1Row`.

-1.3.  При возврате из ChatActivity  происходит автозаполнение поля request1Row  из буфера обмена (выбранным в окне ChatActivity переписки с ботом  ) 
1.4. Если стереть вручную путем редактирования поле request1Row , затем выйти из панели настроек ,и потом зайти обратно , то поскольку поле request1Row пусто, то при тапе на него должно открывается окно ChatActivity переписки с ботом (по п. 1.2.)   

1.5. При тапе на уже ранее заполненное поле request1Row ничего открывать не надо, а пользователю должны быть доступны все стандартные ф-ции редактирования текстовых редактируемых полей (с стандартным всплывающим при долгом тапе мень "выделить, выбрать все, удалить ")
1.6. редактируемое поле request1Row должно находиться в 1й строке (на 1м вертикальном уровне ) с заголовком "Запрос 1", но правее его (не загораживая заголовок собой)

2. Заголовки редактируемых и обновляемых полей :
 
При ЛЮБЫХ непонятках, сомнениях и отходе от задач speciification.md сначала останавливайся и уточняй - задавай уточняющие вопросы!!!

fix build errors
