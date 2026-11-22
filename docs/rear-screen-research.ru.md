# Neon Tuner

Лёгкий хроматический тюнер для Android. Написан на Kotlin и обычных Android View, без библиотек. Release-APK весит около 90 КБ.

- Сверху нота с октавой, под ней шкала от −50 до +50 центов с подсвеченной стрелкой и зелёной зоной «в строю».
- Под шкалой поле с историей. Каждое измерение оставляет след, который уходит вниз (видно 6 с). По следу видно, куда уходит нота, пока крутишь колок. Там, где началась новая нота, подписано её название.
- Пресеты: **Пианино** (26–4300 Гц, окно 8192, высокая чувствительность), **Гитара**, **Укулеле** (низкая чувствительность, чтобы не реагировать на шум), **Бас**.
- Ползунок **Чувствительность** задаёт порог громкости (от −30 до −80 dBFS) и минимальную «чистоту» тона (MPM clarity от 0.95 до 0.72). Ползунок **Плавность стрелки** задаёт силу сглаживания. Ещё есть эталон **A4** от 415 до 466 Гц.
- Высота тона определяется алгоритмом McLeod Pitch Method через FFT-автокорреляцию, с параболической интерполяцией и уточнением по нескольким периодам. По юнит-тестам ошибка не больше 0.5 цента от A0 до C8.
- На заднем экране показывается мини-версия: нота, шкала и история. Подробности ниже.

## Сборка и установка

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

Release-APK подписан debug-ключом и ставится сразу. Тесты: `.\gradlew.bat testDebugUnitTest`.

Демо без инструмента (только debug-сборка): синтетическая «струна» проходит через настоящий детектор.

```powershell
adb shell am start -n com.neontuner/.MainActivity --ez demo true
```

## Проверено на Xiaomi 18 Pro Max (OS4.0.7.0.XEOEUXM, EEA, Android 17)

- Задний экран — `displayId 1`, 976×596, internal, `FLAG_PRESENTATION|FLAG_TRUSTED|FLAG_OWN_DISPLAY_GROUP`. В `DISPLAY_CATEGORY_PRESENTATION` он **не** попадает.
- Запуск Activity туда проверяет `ActivityStarterImpl.isShouldShowOnRearDisplay` в `/system_ext/framework/miui-services.jar`. Правило: meta-data **`miui.rear.policy`** у activity (`0` — запрет, `1` — разрешено). Если у activity ключа нет, берётся значение у application (`1` — разрешено). Ещё есть встроенный белый список `REAR_SCREEN_METADATA_WHITE_LIST`. Без ключа запуск обрывается с кодом **102** (`should not allow app = … show on rear display`).
- Xiaomi Camera (`/product/priv-app/MiuiCamera`) объявляет `miui.rear.policy = 1`. Мы делаем так же для `RearActivity`, и запуск через `ActivityOptions.setLaunchDisplayId(1)` работает без root и Shizuku.
- **Окна** обычных приложений (Presentation, `TYPE_APPLICATION_OVERLAY`) на задний экран не пускаются: `Not allow non-system app add window type = 2037 on rear display`. Поэтому способы 1–2 из таблицы ниже на HyperOS не работают и остаются только запасным вариантом для других устройств.
- Когда задний экран засыпает или на основном нажимают HOME, `com.xiaomi.subscreencenter` возвращает поверх свой экран с часами. Тюнер снова открывается на заднем экране, когда вы возвращаетесь в приложение.

## Как приложение выводит тюнер на задний экран

Приложение пробует три способа по очереди:

| # | Способ | Что нужно | Когда работает |
|---|---|---|---|
| 1 | Окно `TYPE_APPLICATION_OVERLAY` на втором дисплее, его держит foreground-сервис с типом `microphone` (`RearOverlay`) | Разрешение «Поверх других приложений» (кнопка **Работа в фоне**) | Если HyperOS отдаёт задний дисплей через `DisplayManager`. Продолжает работать, когда основной экран погашен |
| 2 | Стандартный `Presentation` (`RearPresentation`) | Ничего | То же условие, но только пока приложение открыто на основном экране |
| 3 | `RearActivity`, запущенная прямо на заднем дисплее | ПК с adb или Shizuku | `adb shell am start --display 1 -n com.neontuner/.rear.RearActivity` |

Экран **⚙ → Диагностика экранов** перечисляет все дисплеи: id, имя, размер, флаги, `DISPLAY_CATEGORY_PRESENTATION`. Там же есть кнопки «Показать A4» (тест Presentation) и «Запустить тюнер» (`setLaunchDisplayId`). С этого экрана и нужно начинать проверку на телефоне.

Отступ под блок камер: по данным сообщества, задняя панель 17 Pro Max — это 976×596, а камеры закрывают левые ~296 px (≈30 %). Отступ включается переключателем.

---

## Исследование: задний экран Xiaomi (HyperOS) для сторонних приложений

> Важно: все найденные источники относятся к **Xiaomi 17 Pro / 17 Pro Max** (китайская прошивка, HyperOS 3, Android 16). Ни одного источника про «18 Pro Max» или про европейскую глобальную прошивку я не нашёл. Всё ниже нужно проверить на вашем телефоне.

### 1. Официальная документация Xiaomi

**[方案提报说明 (pId=2144)](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2144)**: про оформление заявок на «Супер-остров» (超级岛). Про задний экран там одна фраза:
> 「小折叠/背屏手机无需单独适配，由小米侧自行适配」
> «Для раскладушек и телефонов с задним экраном отдельная адаптация не нужна, Xiaomi адаптирует сама».

Это значит, что стандартные шаблоны Focus-уведомлений и острова Xiaomi сама показывает и на заднем экране. Своего View там нет.

**[常见Q&A (pId=2146)](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146)**: FAQ по Focus-уведомлениям (焦点通知).
> 「OS1需要单独适配…当前阶段不建议接入了。OS2可根据不同模板单独适配」
> «OS1 требует отдельной адаптации… подключать сейчас не рекомендуется. Под OS2 можно адаптироваться по шаблонам». Для OS3 те же шаблоны, что и для OS2.

- Доступ выдают по заявке на mipush-permission@xiaomi.com. По умолчанию функция выключена.
- Проверка доступа: `content://miui.statusbar.notification.public`, метод `canShowFocus`, extra `package`.
- Данные передаются в `miui.focus.param` (JSON, ≤3072 байт), картинки — в `miui.focus.pic_xxx`.
- Это только системные шаблоны. Произвольный View и обновление 40 раз в секунду не поддерживаются.

**[m.mi.com/appcard](https://m.mi.com/appcard)**: на странице только слоган «百变背屏 万千可能» («Задний экран на любой вкус, бесконечные возможности»). Технической информации нет.

Вывод: **официального API для произвольного View на заднем экране нет.** Официально доступны только системные шаблоны (Focus-уведомления, остров, музыкальная карточка), и Xiaomi сама переносит их на задний экран.

### 2. Сообщество (неофициально)

| Источник | Устройство и прошивка | Что выяснили |
|---|---|---|
| [V2EX t/1180257](https://www.v2ex.com/t/1180257) | 17 Pro | Разработчик спрашивает, где документация. Ответ ссылается на [pId=2026](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2026): 「这个里面有一部分 应该是和适配外屏一样的」 — «часть, видимо, совпадает с адаптацией под внешний экран (Flip)» |
| [Peter23687/…rear-screen-customizability](https://github.com/Peter23687/Xiaomi-17-Pro-Max-rear-screen-customizability) | 17 Pro Max | HyperOS запрещает сторонним приложениям **открываться** на заднем экране («error 102»), но **не запрещает переносить** уже запущенную задачу: `am display move-stack <task> 1` через Shizuku. Мешает `com.xiaomi.subscreencenter`. Панель 976×596, камеры закрывают левые 296 px |
| [totallynotkasai/mi-back-screen](https://github.com/totallynotkasai/mi-back-screen) | 17 Pro Max, HyperOS 3 (Android 16) | 「HyperOS doesn't let normal apps draw on the rear display」. Использует `am start --display 1`, `am display move-stack` через Shizuku |
| [Tnina2612/YTRear](https://github.com/Tnina2612/YTRear) | 17 Pro, OS3.0.318.0.WBLCNXM и OS4.0.6.0.XBLCNXM (CN) | Музыкальный виджет заднего экрана (`unified.music:music_media`) работает только для белого списка package name. Работает без root и Shizuku, но с подменой applicationId |
| [Orynnx/OuterView](https://github.com/Orynnx/OuterView) | 17 Pro | Импорт «умных приложений» (JsCanvas) в системный хост заднего экрана. Нужны root и LSPosed. 「不同 HyperOS 版本的兼容性需要单独验证」 — «совместимость с разными версиями HyperOS нужно проверять отдельно» |
| [cwuom/NeriPlayer#99](https://github.com/cwuom/NeriPlayer/issues/99) | серия 17 | 「可能是小米有白名单机制」 — «видимо, у Xiaomi белый список». 「没找到直接关于背屏的开发文档和接入API」 — «документации и API для заднего экрана не нашли» |
| [CSDN, Live2D](https://bbs.csdn.net/weixin_28292761/article/details/100243830) | 17 Pro | Перебирает `getDisplays()` и показывает `Presentation` на первом не-основном дисплее: 「关键在于正确识别背屏的Display ID并创建专属的Window」 — «главное — правильно определить Display ID заднего экрана и создать для него своё окно». Статья частично платная, версия прошивки не указана, не проверено |
| [linux.do t/3148782](https://linux.do/t/topic/3148782) | — | Не открылась (403) |

**Камера Xiaomi** (видоискатель на заднем экране) — системное приложение. Она доказывает только то, что задний экран умеет показывать живой контент. О правах сторонних приложений это ничего не говорит.

### 3. Android Presentation API: минимальный тест

Готовый тест встроен в приложение (**Диагностика экранов**). Тот же код отдельно:

```kotlin
class A4Test : Activity() {
    private var p: Presentation? = null
    override fun onResume() {
        super.onResume()
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        displays.forEach { Log.i("A4Test", "presentation display: ${it.displayId} ${it.name}") }
        val d = displays.firstOrNull()
        if (d == null) { Log.w("A4Test", "no presentation display"); return }
        p = object : Presentation(this, d) {
            override fun onCreate(b: Bundle?) {
                super.onCreate(b)
                setContentView(TextView(context).apply {
                    text = "A4"; textSize = 96f; gravity = Gravity.CENTER
                    setTextColor(Color.CYAN); setBackgroundColor(Color.BLACK)
                })
            }
        }.also { it.show() }  // WindowManager.InvalidDisplayException if the OS refuses
    }
    override fun onPause() { p?.dismiss(); p = null; super.onPause() }
}
```

На эмуляторе с симулированным вторым экраном 976×596 (`adb shell settings put global overlay_display_devices 976x596/240`) проверено следующее: «A4» через Presentation показывается, мини-тюнер через overlay показывается и не пропадает в фоне, `am start --display 2 … RearActivity` работает.

### 4. Шаги проверки на вашем телефоне

1. Установите APK, разрешите микрофон и уведомления.
2. Откройте **⚙ → Диагностика экранов** и сфотографируйте или запишите список дисплеев. Главный вопрос: есть ли второй дисплей и стоит ли у него `category PRESENTATION: true`.
3. Нажмите **Показать «A4»**. Если «A4» появилось на заднем экране, способ 2 работает.
4. Нажмите **Работа в фоне** и дайте разрешение «Поверх других приложений». Вернитесь в тюнер, переверните телефон и проверьте, что мини-тюнер остаётся на заднем экране (способ 1).
5. Если второй дисплей не виден или показ запрещён, включите отладку по USB и выполните:
   ```powershell
   adb shell dumpsys display | Select-String "mDisplayId=|DisplayInfo{"
   ```
   ```powershell
   adb shell am start --display 1 -n com.neontuner/.rear.RearActivity
   ```
6. Если и это блокируется (error 102), остаётся перенос задачи: `adb shell am stack list`, затем `adb shell am display move-stack <taskId> 1` (или то же через Shizuku).

### 5. Что ещё не проверено (европейская модель)

- Отдаёт ли европейская HyperOS задний дисплей через `DisplayManager` вообще и под каким id. Все отчёты выше — с китайских прошивок.
- Есть ли на глобальной прошивке блокировка error 102 и `com.xiaomi.subscreencenter`.
- Пускает ли HyperOS окна `TYPE_APPLICATION_OVERLAY` на задний дисплей.
- Не гасит ли система задний экран и не режет ли микрофон, когда основной экран выключен. Для этого foreground-сервис с типом `microphone`; возможно, понадобятся «Автозапуск» и «Без ограничений» в настройках батареи.
- Точный размер панели и положение камер на вашей модели (отступ 30 % взят для 17 Pro Max).
- Доступ к Focus-уведомлениям (заявка в Xiaomi) — единственный официальный путь, но он даёт только шаблон: ноту и центы текстом, без живой шкалы.
