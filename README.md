## TeleRay + v2rayTg it is clon(fork) + of Telegram messenger for Android + interaction with v2rayTg.
v2rayTg - это форк успешного проекта v2rayNG с добавлением IPC-ф-ций для взаимодействи с TeleRay.(
А TeleRay - это форк официально рекомендованных Telegram [исходников](https://github.com/DrKLO/Telegram)  для создания своего кастомного клиента Telegram .
TeleRay.apk и v2rayTg.apk запускаюся как отдельные apk приложения на однмом устройстве android. 
(v2rayTg собирается из модуля v2ray проекта).

При затруднении соединений Telegram (а заодно ютуп, и др.и заруб.сайтов) ПРИ РАБОТАЮЩЕМ 
ИНТЕРНЕТЕ, И ЕСЛИ РАНЕЕ, ПРИ РАБОТАЮЩЕМ Telegram, польз-ль уже настроил панель упр-я подписками 
и v2rayTg, то он просто жмет кн. авто-обновления ....которая запускает цикл,  ф-ции в котором действовуют по след. общей логике:
1. Попытка сразу "починить" управляемый по IPC v2rayTg, проверив, отсортировав и выбрав лучший из
   имеющихся в  v2rayTg профиль
2. Если не помогло, - "поскрести по сусекам" ранее сохраненных профилей в базах
   , повторно запросить у ботов по хранимых тамже командам
   запросов, все полученное -отправить в v2rayTg, снова протестировать в v2rayTg, отсортировать,
   включить лучший.
3. Если не сработали 1,2, - работа с прокси, т.е. 3.1. включение proxyList Telegram, если не помогли
   имеющиеся таам, то 3.2. добавление  туда (с проверкой и включением)  прокси, сохраненных ранее (в фазах резервирования)  в сумм.кол-ве, указанном на панели .java  в поле  "мин. ко-во MTProxy".
4. Если и это не помогло, попытка прочесть еще видимые последние сообщения в каналах MTProxy,
   ранее запомненных в панелях , и попытка запросить прокси у ботов из
   панелей . Добавление в proxyList Telegram.
5. Как только восстановилось подключение (например после действий предыдущ. цикла, с прокси,
   -снова п.2.,т.е. получение от ботов и обновление профилей в
   v2rayTg.
6. Если коннект через v2rayTg и в порядке , - отключение глючных прокси и переход к резервированию.

7. Кн. можно и не отжимать после восстановления соединения (если не мешают всплывающие иногда сообщения о том, что делается) ,а увеличить время цикла
   с 1 до 10 мин.(оптимально) , тогда раз в 10 мин. будет проверяться соединение через v2rayTg, , проверка и резервирование свежих ключей и прокси.

8. Кн. запуска  авто-обновления - за код-паролем (придется его сделать , но зато благодаря этому по
   внешн.виду этот клон Телеграм нельзя отличить от стандартного пока не войдешь в настройках в
   меню    "код-пароля").
9. команд IPC назначения/управления конкретного профиля - нет (только команда выбора наилучшего,
   определяемого уже самим v2rayTg самостоятельно)
Скачать APK (подписанные разработчиком) тут [TeleRay](https://github.com/vit1967/TeleRay/releases/download/v1.0/teleray.apk), [v2rayTg](https://github.com/vit1967/TeleRay/releases/download/v1.0/v2ray_standalone-release.apk) .
ВНИМАНИЕ! Приложение после запуска регистрируется как стандартный новый клиент Telegram. Напоминаю,
что в РФ не приходят СМС авторизации свежеустановленных кл-ов Telegram, но Telegram рассылает коды 
авторизации и через свой специальный чат "Telegram Notification" (mobile 42777), поэтому перед 
настройкой этого нового кл-та Telegram, сначала войдите в свой старый клиент (на этом или др. утр-ве) и найдите 
откройте там чат с "Telegram Notification", по которому, после ввода вашего ранее 
зарегистрированного номера телефона, и придет код авторизации для нового клиента.

Проект успешно собирается в Inlellij IDEA и работает на Android. Сначала лучше запускать v2rayTg,
потом можно нажимать кн. авто-обновления в TeleRay.

После ввода код-пароля увидете кнопку "V2ray MTProxy auto upd.". нажаd - входите в панель упр-я. 
подписками и автомтическими обновлениями См. [инструкцию](https://github.com/vit1967/TeleRayV2rayTg/blob/main/docs/V2RAY_SUBSCRIPTION_USER_GUIDE.md)

## From DrKLO Telegram
(оставил без изменений, так как функциональность взятого за оснву Telegram не менял)
## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 3.4, Android NDK rev. 20 and Android SDK 8.1

1. Download the Telegram source code from https://github.com/DrKLO/Telegram ( git clone https://github.com/DrKLO/Telegram.git )
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
