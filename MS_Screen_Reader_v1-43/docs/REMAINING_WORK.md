# MS Screen Reader — Remaining Work (as of v1.43)

## ✅ पूरा हो चुका

**v1.43 — वॉल्यूम-बटन से call answer न होना (permission देने के बाद भी) — जांच व fix:**

- असली वजह पक्की तरह से pin नहीं हो पाई बिना असली फ़ोन पर test किए
  (TalkBack में यह feature है ही नहीं, तुलना के लिए कोई reference
  नहीं) — पर कोड में एक पक्का बग मिला: `answerCall()` सिर्फ़
  `SecurityException` को चुपचाप निगल जाता था, कोई feedback नहीं देता
  था। अब: (1) कॉल से ठीक पहले permission दोबारा असली में check करता
  है (पुराने grant पर भरोसा नहीं — Android महीनों तक न खुलने वाले app
  की runtime permission अपने-आप वापस ले सकता है), (2) कोई भी वजह से
  fail होने पर साफ़ बोलकर बताता है क्यों (permission नहीं है / system
  ने मना किया / और कुछ) — पहले पूरी चुप्पी थी
- **ध्यान दें**: OS की Permission (ANSWER_PHONE_CALLS grant) और
  app के अंदर की "Volume-button answer" setting (जो default में OFF
  है) — ये दो अलग चीज़ें हैं। सिर्फ़ permission देने से यह feature on
  नहीं होता, app के अंदर का toggle भी अलग से on करना ज़रूरी है
- अगर permission + in-app toggle दोनों on होने पर भी न चले, तो कुछ
  OEM (Xiaomi/MIUI, Vivo, Oppo/Realme, कुछ Samsung) फ़ोन पर battery
  optimization/autostart की अपनी अलग whitelist होती है, जो standard
  Android Settings से अलग है — वो भी जांचनी होगी

**v1.42 — असली TalkBack के सोर्स से 8 और bugs का root-cause fix (गहराई से verify किया, कोई guess नहीं):**

- **Nav-bar बटन उंगली रखकर locate न होना**: सिर्फ़ click event पर बोलता
  था, hover/touch-explore event पर कुछ नहीं। अब hover पर भी बोलता है;
  उसी label पर आने वाला click 800ms के अंदर duplicate मानकर skip होता
  है (double-announce वापस नहीं आता)
- **List खुलने पर focus कहीं भी कूद जाना — असली वजह**: `refresh()` और
  `restoreRememberedFocus()` सिर्फ़ plain text से मैच करते थे, पूरी नई
  list में कहीं भी। अब `refresh(preservePosition)` — genuine
  window-change पर position preserve करने की कोशिश ही नहीं करता (सिर्फ़
  same-window content-refresh पर); `restoreRememberedFocus()` अब text
  के साथ resource-id/class-name भी मिलाता है (असली TalkBack के
  structural node-path match जैसा)
- **वॉल्यूम-बटन से बंद करने पर भी background में बोलते रहना — असली
  वजह**: `MSScreenReaderService` और `MSNotificationListenerService` के
  अपने-अपने अलग `TtsManager` instance, अलग-अलग `isMuted`। अब
  `isMuted` companion object में shared/process-wide है — कहीं से भी
  mute करने पर दोनों mute
- **एक ही notification बार-बार बोलना**: `onNotificationPosted()` में
  कोई dedup नहीं था (Android अपडेट पर भी दोबारा कॉल करता है)। अब
  `sbn.key` के हिसाब से last-spoken text याद रखकर सिर्फ़ genuinely
  बदला हुआ content ही बोलता है
- **List/menu के आख़िरी item पर पूरी चुप्पी**: `move()` का null case
  कुछ नहीं करता था। अब असली TalkBack जैसे "No more items"/"No previous
  items" बोलता है + boundary earcon बजाता है
- **Accessibility volume का कोई तरीका न होना**: उंगली screen पर रखकर
  hardware वॉल्यूम बटन दबाने पर असली TalkBack accessibility stream की
  volume सीधे बदलता है (`AudioManager.adjustStreamVolume`), चाहे उस
  वक़्त कुछ बोल रहा हो या नहीं। अब हम भी `TYPE_TOUCH_INTERACTION_START/
  END` से उंगली की स्थिति track करके यही करते हैं (नया setting:
  accessibilityVolumeViaTouchEnabled, default on)
- **Gesture-settings screens में स्क्रीन-रीडर को पता न चलना कि Spinner
  किस register के लिए है**: label TextView और Spinner अलग-अलग
  accessibility nodes थे। अब Spinner पर हमेशा
  `contentDescription = "<register label>, currently <value>"` सेट
  रहता है (DefaultGestureSettingsActivity और
  PerAppGestureSettingsActivity, दोनों में)
- **App-list/contact-list में ग़लत/आधा टेक्स्ट बोलना**: `descendantText()`
  पहला मिला non-blank text लेकर रुक जाता था (जैसे नाम की जगह सिर्फ़
  "Mobile")। अब सारे meaningful descendant texts जोड़कर बोलता है
  (असली TalkBack के `CompositorUtils.joinCharSequences` जैसा), जैसे
  "Priya Sharma, Mobile"
- (जांचा पर bug नहीं निकला: `onGesture(int)` बनाम
  `onGesture(AccessibilityGestureEvent)` का double-fire — असली AOSP
  source से verify किया, safe है, कोई बदलाव नहीं किया)

**v1.41 (पुराना) — GitHub Actions compile error fix:**

- `MainActivity.kt` की `openAccessibilitySettingsForThisService()` में
  `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` और
  `Settings.EXTRA_COMPONENT_NAME` — दोनों hidden/@SystemApi फ़ील्ड हैं,
  public SDK (compileSdk 35) में मौजूद नहीं, इसलिए हर बार
  "Unresolved reference" पर build fail हो रहा था। Fix: action string
  को raw string `"android.settings.ACCESSIBILITY_DETAILS_SETTINGS"` से
  और extra key को public `Intent.EXTRA_COMPONENT_NAME` से बदल दिया —
  runtime व्यवहार बिल्कुल वही रहता है, सिर्फ़ compile-time पर public API
  का इस्तेमाल होता है।

**v1.40 — असली TalkBack के सोर्स कोड से तुलना करके 5 पुराने bugs का root-cause fix (कोई नया guess नहीं, हर एक TalkBack के असली algorithm से मिलाकर verify किया):**

- **Nav-bar Back/Home/Overview और launcher "Apps" बटन का double-tap से
  activate न होना — असली वजह मिली**: `NodeNavigator.collect()` एक
  clickable container (जिसका अपना कोई text/label नहीं) और उसके अंदर
  के non-clickable icon/label child (जिसमें text है) — दोनों को अलग-अलग
  stop बना देता था। नेविगेट करने पर उसी labeled (पर non-clickable)
  icon पर focus जाता था, double-tap पर `ACTION_CLICK` उसी को भेजा
  जाता जो सुनता ही नहीं। असली TalkBack का `AccessibilityNodeInfoUtils`
  ऐसे non-actionable, text-वाले descendants को अपने clickable ancestor
  में merge कर देता है — वही approach अब यहाँ भी: `collect()` में नया
  `hasActionableAncestor()` check ऐसे बच्चों को अलग stop बनने से रोकता
  है, `rawText()` में नया `descendantText()` fallback ancestor को उसका
  label वापस दिला देता है
- **List "टुकड़ों में"/आधा-अधूरा पढ़ना, बीच में शुरुआत पर वापस कूद
  जाना — असली वजह मिली**: `NodeNavigator.move()` सिर्फ़ उन nodes में
  घूमता था जो RecyclerView/GridView ने अभी तक inflate किए हैं (पूरी
  लिस्ट नहीं, सिर्फ़ visible हिस्सा)। उस हिस्से के आख़िरी item पर
  पहुँचते ही, अगर `wrapNavigationEnabled` on था, कोड सीधे वापस पहले
  item पर कूद जाता — असली list अभी बाकी होने के बावजूद "list ख़त्म हो
  गई" मान लेता। नया `scrollAtEdge()` अब पहले container को
  ACTION_SCROLL_FORWARD/BACKWARD से scroll करने की कोशिश करता है; नया
  `NodeNavigator.SCROLL_PENDING` marker + `MSScreenReaderService`'s नया
  `handleMoveWithScrollRetry()` scroll के 300ms बाद refresh करके move
  दोबारा try करता है। सिर्फ़ तभी wrap/stop होता है जब container वाकई
  और आगे scroll नहीं कर सकता
- **300ms का delay अभी अंदाज़े से है, असली फ़ोन पर टेस्ट करके tune करना
  होगा** — तेज़/धीमे container पर कम/ज़्यादा चाहिए हो सकता है
- **नया `GestureAction.SWIPE_ACTIVATE`** (2-finger single tap पर
  default) — जान-बूझकर manual fallback, ACTIVATE का automatic fallback
  नहीं, क्योंकि यह पहले से बताना मुमकिन नहीं कि कौन सा element
  fundamentally एक swipe-only target है (जैसे Microsoft Launcher का
  "Apps" handle, जो सिर्फ़ swipe-up से खुलता है, tap/double-tap/
  coordinate-tap किसी से नहीं) बनाम सिर्फ़ ACTION_CLICK-broken element।
  असली TalkBack का अपना double-tap handler भी सिर्फ़ ACTION_CLICK भेजता
  है (कोड में verify किया: `FocusActorForTapAndTouchExploration.
  performClick()`), कोई swipe fallback नहीं — यानी ऐसे बटन पर TalkBack
  भी tap से fail करता, वहाँ असल फ़र्क़ यह था कि focus सही (clickable)
  ancestor पर जा रहा था, जो ऊपर वाले fix से यहाँ भी अब सही हो जाना
  चाहिए; SWIPE_ACTIVATE सिर्फ़ genuinely swipe-only elements के लिए है
- **ON/OFF switch चालू करने पर generic Accessibility Settings लिस्ट न
  खुलकर सीधे इसी service के अपने toggle पेज पर जाना** (API 30+
  `ACTION_ACCESSIBILITY_DETAILS_SETTINGS`, पुराने Android/असमर्थ OEM
  स्किन पर पुराने generic list पर fallback) — extra "list में ढूंढो"
  कदम हट गया
  - **यह अब भी नहीं बदलता**: Android 13+ की Restricted Settings
    पाबंदी (v1.22 का नोट) — हर fresh install के बाद एक बार App Info
    → ⋮ → "Allow restricted settings" अब भी करना होगा, यह OS-level
    सुरक्षा है, किसी भी कोड से बायपास नहीं होती
- **जांच में पाया, कोड नहीं बदला** (पहले से सही/design के मुताबिक था):
  - Volume Up+Down साथ दबाना = suspend/resume speech — पहले से मौजूद
    (`volumeSimultaneousAction` का default `TOGGLE_SPEECH`), बस
    Settings में "Volume Shortcuts" toggle on होना ज़रूरी है
  - Accessibility volume (finger/touch पर बढ़ना-घटना) — TtsManager
    पहले से बिल्कुल TalkBack जैसा सही तरीका इस्तेमाल करता है
    (`USAGE_ASSISTANCE_ACCESSIBILITY`); यह सिर्फ़ तभी काम करता है जब
    उसी वक़्त कुछ बोला/earcon बजाया जा रहा हो — यह bug नहीं, Android का
    design है, TalkBack में भी वैसा ही
- **Launcher-conflict (Microsoft Launcher का अपना swipe-up gesture हमारे
  gesture handling से टकराना) अभी भी खुला है** — इस session में हल
  नहीं हुआ, असली device पर ऊपर के fixes टेस्ट करने के बाद exact symptom
  (अब भी टकराव है या नहीं) दोबारा चेक करना होगा
- **double-tap-to-click का असली platform behavior अब भी असल डिवाइस पर
  unverified है** — यह session TalkBack के असली सोर्स से तुलना करके
  बना है, पर बिना असली फ़ोन पर चलाए यह दावा नहीं किया जा सकता कि हर
  case ठीक हो गया
- अभी तक real device पर test नहीं हुआ

**v1.39 — एक पेस्ट किए गए AI-जनरेटेड डॉक्यूमेंट का सत्यापन + custom labeling:**
- User ने एक लंबा डॉक्यूमेंट पेस्ट किया (किसी और AI का analysis, हमारे असली v1.38
  कोड पर आधारित नहीं) जिसमें कई "समस्याएं" और उनके "समाधान" बताए गए थे। हर दावे
  को असली कोड से मिलाकर चेक किया — ठीक उसी तरह जैसे पहले "Document 40" वाले दावे
  को item 9 में rejected किया गया था:
  - **पहले से ठीक/बेहतर मौजूद** (कोई बदलाव नहीं किया): accessibility-volume audio
    routing (TtsManager/SoundSchemeManager में पहले से सही AudioAttributes),
    TTS queue mode (पहले से QUEUE_FLUSH, सिर्फ़ focused node बोलता है — पूरा
    subtree नहीं), volume-key suspend/resume combos (पहले से v1.27 में ज़्यादा
    flexible/configurable रूप में मौजूद), role/"Button" announcement (पहले से
    ज़्यादा complete — checkbox/switch/radio/slider/dropdown/edit-box सब), और
    nav-bar Back/Home/Overview का क्लिक न होना (हमारा मौजूदा coordinate-tap
    fallback — देखें `MSScreenReaderService`'s `GestureAction.ACTIVATE` — डॉक्यूमेंट
    के resource-ID-specific global-action सुझाव से ज़्यादा general है, किसी भी
    view पर काम करता है सिर्फ़ नेव-बार पर नहीं)
  - **असली, नई, लागू की गई चीज़ें** (नीचे देखें): दो missing accessibility flags,
    custom labeling feature
- **`accessibility_service_config.xml` में दो missing flags जोड़े**:
  `flagReportViewIds` (बिना इसके `viewIdResourceName` ज़्यादातर दूसरी apps के
  नोड्स पर खाली मिलता — nav-bar detection, element-ID fallback, और अब custom
  labels तीनों इसी पर निर्भर हैं) और `flagIncludeNotImportantViews` (कुछ OEM
  icon-only बटन्स को "not important for accessibility" मार्क करते हैं, जो
  इस flag के बिना node tree में आते ही नहीं)
- **Custom Labeling ("Add label") — नया फ़ीचर** (पहले v1.15 में "architecture से
  बाहर" माना गया था, पर असल में SharedPreferences से ही feasible निकला):
  - नई `labels/CustomLabelManager.kt` — SharedPreferences-based storage,
    key = `"packageName/viewIdResourceName"` (resource ID ही एकमात्र stable
    handle है; बिना ID वाले elements को label नहीं किया जा सकता — यह announce
    होता है, चुपचाप fail नहीं होता)
  - नई `labels/CustomLabelActivity.kt` — Add/Edit/Remove label dialog (blank
    छोड़ने पर label हट जाता है, ScreenSearchActivity जैसा pattern पर बना पर
    बिना किसी deferred-run की ज़रूरत के, क्योंकि label save करना सिर्फ़ एक
    तुरंत SharedPreferences write है)
  - `NodeNavigator.announceableDescription()` में custom label अब सबसे पहले
    चेक होता है (rawText/element-ID fallback से भी पहले)
  - नया `GestureAction.ADD_CUSTOM_LABEL` — हाल के दूसरे नए actions (PASTE,
    SCREEN_SEARCH वगैरह) जैसे इसे भी कोई hardcoded default gesture नहीं दिया
    गया, Default/Per-App Register Setting screens से खुद असाइन करना होगा
- अभी तक real device पर test नहीं हुआ

**v1.38 — बाकी बचे तीन multi-finger फ़ीचर (एक साथ):**
- **3-finger tap+hold → Search screen**: नया `GestureAction.SCREEN_SEARCH`,
  नई `ScreenSearchActivity` (query टाइप करने के लिए छोटी dialog-themed
  स्क्रीन)। असली search उस पुरानी app के वापस foreground में आने पर
  होती है (`MSScreenReaderService.requestScreenSearch` +
  `tryRunPendingScreenSearch`, `tryRestoreRememberedFocus` वाले पैटर्न
  पर बना), क्योंकि जब तक यह search screen खुली है तब तक
  `rootInActiveWindow` उसी का होता है, टारगेट ऐप का नहीं।
  `NodeNavigator.searchAndFocus()` (नया) current flatNodes में
  case-insensitive text match ढूंढ कर वहां focus ले जाता है।
- **4-finger swipe left/right → पिछला/अगला Container**: नए
  `GestureAction.PREVIOUS_CONTAINER`/`NEXT_CONTAINER`,
  `NodeNavigator.moveToNextContainer()` (नया) — असल list/grid ancestor
  *node* (सिर्फ़ "list"/"grid" वाला label नहीं) से मैच करता है, ताकि
  एक ही स्क्रीन पर दो अलग RecyclerViews को दो अलग containers माना जाए,
  एक जैसे label की वजह से मिला न दिया जाए।
- **4-finger single tap → Gesture Practice (tutorial mode)**: नया
  `GestureAction.OPEN_GESTURE_PRACTICE`, नई
  `GesturePracticeActivity` — खुलते ही
  `MSScreenReaderService.setPracticeMode(true)` कॉल करती है, जिससे
  `dispatchGesture()` हर gesture को (चाहे कितनी भी फ़िंगर की हो) उसकी
  real action चलाने की बजाय सिर्फ़ नाम बोल देता है (TalkBack के अपने
  "practice gestures" जैसा) — यह स्क्रीन बंद होने पर (Done बटन या
  onDestroy, दोनों से) वापस `false` हो जाता है।
- **Copy/Paste (पिछले session का बाकी हिस्सा)**: swipe left-then-right
  (आख़िरी unused 1-finger reversal gesture) पर नया
  `GestureAction.PASTE` — `NodeNavigator.pasteIntoCurrent()` फोकस्ड
  editable node पर `ACTION_PASTE` भेजता है। Copy पहले से मौजूद था
  (`ReadingGranularity.COPY` मोड)।
- सभी नए actions Settings UI (Default/Per-App Register Setting) में
  अपने-आप दिख जाते हैं क्योंकि वो सीधे `GestureAction.entries` से लिस्ट
  बनाती हैं — अलग से कोई UI कोड नहीं जोड़ना पड़ा
- **सीमाएं**:
  - Screen Search सिर्फ़ मौजूदा स्क्रीन के flatNodes में match ढूंढता
    है, scroll करके आगे के off-screen items में नहीं (असली TalkBack भी
    ऐसा ही करता है)
  - Container navigation सिर्फ़ ListView/RecyclerView/GridView
    ancestors को container मानता है (वही सीमा जो पहले से
    `containerLabelFor`/verbosity announcements में है) — किसी स्क्रीन
    पर कोई container ही न हो तो gesture चुपचाप कुछ नहीं करता
  - Gesture Practice में भी touch-explore + double-tap से Done बटन तक
    पहुंचना अभी भी काम करता है (वो हमारे gesture-dispatch से नहीं गुज़रता,
    सीधे Android का built-in activation है) — सिर्फ़ swipe/multi-finger
    gestures intercept होते हैं
- अभी तक real device पर test नहीं हुआ

**v1.37 (यह वाला) — Paste gesture (Copy granularity का जोड़ीदार):**
- नया `GestureAction.PASTE` — जो आख़िरी 1-finger reversal gesture अभी
  तक unused था (swipe left-then-right), अब उस पर assign किया गया।
  अब सभी चारों reversal gestures (up-then-down, down-then-up,
  right-then-left, left-then-right) के पास कोई-न-कोई default action है।
- `NodeNavigator.pasteIntoCurrent()` (नया) — जिस node पर अभी
  accessibility focus है, अगर वो editable text field है तो उस पर
  `AccessibilityNodeInfo.ACTION_PASTE` भेजता है; अगर focus किसी node
  पर नहीं है या वो editable नहीं है तो सीधे `false` लौटा देता है (ताकि
  service "यहाँ paste नहीं हो सकता" बोल सके, चुपचाप कुछ न करने की बजाय)
- `MSScreenReaderService.pasteFromClipboard()` (नया) — system clipboard
  से paste करता है (वही clipboard जिसमें COPY granularity की copy/append
  swipes लिखती हैं, पर सिर्फ़ अपने लिखे तक सीमित नहीं — किसी भी ऐप का
  आख़िरी copied टेक्स्ट paste हो सकता है)। सफल होने पर "Pasted" बोलता है
  + नया `SoundEvent.PASTED` earcon बजता है, असफल होने पर "Cannot paste
  here" बोलता है
- ज़रूरी सीमा: paste की सफलता आख़िरी में target app पर निर्भर है —
  `ACTION_PASTE` सही से dispatch होने पर भी अगर वो app इस action को
  properly handle नहीं करता तो टेक्स्ट फ़ील्ड में कुछ नहीं आएगा (ठीक
  वैसे ही जैसे `ACTION_CLICK` कुछ views पर काम नहीं करता — देखें
  `activateCurrent()` का fallback)
- Settings UI (Default/Per-App Register Setting) में यह अपने-आप दिखने
  लगेगा क्योंकि वो स्क्रीन सीधे `GestureAction.entries` से लिस्ट बनाती
  हैं — कोई अलग UI बदलाव नहीं करना पड़ा
- अभी तक real device पर test नहीं हुआ

**v1.22 (यह वाला) — Accessibility Settings में service का सही तरीके से पहचाना जाना:**
- User ने पिछली installed version में एक issue बताया: Accessibility
  Settings में हमारी service अपने असली नाम/तरीके से पहचानी जाने की
  बजाय अलग तरीके से (एक अलग/generic ग्रुप में, शायद "Downloaded apps"
  जैसे किसी सेक्शन में) दिख रही थी, ठीक एक सीधे on/off toggle की जगह
  कुछ और behavior
- **कोड फिक्स**: `accessibility_service_config.xml` में
  `android:isAccessibilityTool="true"` जोड़ा (API 31+ attribute) — यह
  Android को बताता है कि यह genuinely एक accessibility tool है
  (TalkBack जैसे), जिससे Settings में यह सही ग्रुप/नाम के साथ दिखनी
  चाहिए, किसी generic "downloaded service" warning वाले bucket में
  नहीं
- **ज़रूरी बात जो कोड से ठीक नहीं हो सकती**: Android 13+ का एक अलग,
  OS-level security feature है जिसे **"Restricted Settings"** कहते
  हैं — यह किसी भी sideloaded app (यानी Play Store के बजाय सीधे APK
  install किया गया, जैसे हमारा GitHub Actions वाला build) की
  Accessibility Service toggle को पहली बार में grey/disabled रखता है,
  "For your security, this setting is currently unavailable" जैसा
  message दिखाकर। यह किसी भी app के कोड से बायपास नहीं हो सकता (कोई
  manifest flag, कोई attribute नहीं) — यह जानबूझकर malware से बचाव के
  लिए है, और TalkBack/अन्य Play Store apps इसलिए प्रभावित नहीं होते
  क्योंकि वो session-based installer से आते हैं
  - **इसका user-side हल** (हर install के बाद एक बार करना होगा): APK
    install करने के बाद → फ़ोन की Settings → Apps → MS Screen Reader
    खोलें → ऊपर दाईं तरफ़ तीन-डॉट मेनू (⋮) → **"Allow restricted
    settings"** चुनें (fingerprint/lock माँगेगा) → फिर वापस Settings →
    Accessibility → MS Screen Reader में जाकर toggle चालू करें
  - **वैकल्पिक तरीका**: अगर APK को file manager/browser से सीधे tap
    करने की बजाय किसी session-based installer app (जैसे "SAI - Split
    APKs Installer") से install किया जाए, तो यह sideload नहीं माना
    जाता और restriction लगती ही नहीं
- अभी तक real device पर test नहीं हुआ

**v1.21 — असली इस्तेमाल से मिले 6 फीडबैक फिक्स (TalkBack pattern के हिसाब से), launcher-conflict और app-list-scroll-stuck बाद के लिए टाले गए:**
- **Checkbox → Switch**: सभी 5 settings screens (SettingsActivity,
  GranularitySettingsActivity, NotificationMuteSettingsActivity,
  PerAppGestureSettingsActivity, VerbositySettingsActivity) में plain
  `CheckBox` को `androidx.appcompat.widget.SwitchCompat` से बदला —
  screen reader अब "checkbox" की जगह "switch" बोलेगा
  (`NodeNavigator.elementTypeLabel()` पहले से यह detect करता था)
- **Main Menu में Cancel बटन**: आखिर में जोड़ा, बिना back gesture के
  भी मेनू बंद करने का सीधा तरीका
- **स्क्रीन रीडर के नाम का ON/OFF switch**: MainActivity के ऊपर नया
  "MS Screen Reader" switch — `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
  से असली स्थिति पढ़ता है। बंद करने पर `disableServiceCompletely()`
  चलता है; चालू करने की कोशिश पर (Android की सुरक्षा-पाबंदी की वजह से
  in-app से service enable करना संभव नहीं) सीधे Accessibility Settings
  खुल जाती है और असली स्थिति `onResume()` में वापस पढ़ी जाती है
- **Volume-key gating**: कोड रिव्यू किया — `onKeyEvent()` पहले से एक
  ही साफ़ gate है (सिर्फ ringing के दौरान और setting on होने पर volume
  key consume होती है), कोई leftover listener नहीं मिला।
  `onDestroy()`/`onInterrupt()` में TTS shutdown, sound-scheme release,
  call-handling unregister पहले से ठीक से हो रहे थे — पूरी तरह बंद
  होने का व्यवहार पहले से सही architecture पर था, कोई कोड बदलाव नहीं
  चाहिए था
- **Nav-bar double-announce fix**: नया
  `MSScreenReaderService.navigationBarButtonLabel()` — अगर event
  `com.android.systemui` package से है और resource ID
  back/home/home_handle/recent_apps में से किसी से मैच करे, तो सिर्फ
  click event पर एक बार अपना fixed label ("Back"/"Home"/"Recent apps")
  बोलता है, focus/hover वाला duplicate event उसी बटन के लिए skip हो
  जाता है। Resource ID से match किया, text/contentDescription से नहीं
  — इसलिए gesture-navigation बनाम 3-button-navigation मोड बदलने पर भी
  यह टूटेगा नहीं
- **Vibration feature**: `SoundSchemeManager` में नया — हर SoundEvent
  पर छोटा vibration (`VibrationEffect.createOneShot`, API 31+ पर
  `VibratorManager`, उससे नीचे legacy `Vibrator`), नया
  `settings.vibrationEnabled` (default `true`, TalkBack जैसा),
  sound-scheme folder set होने या `soundSchemeEnabled` से पूरी तरह
  independent — बिना कोई sound folder set किए भी सिर्फ vibration काम
  करेगा। Detail Settings के Sound section में नया toggle। `VIBRATE`
  permission manifest में जोड़ी
- अभी तक real device पर test नहीं हुआ

## ⏸️ जानबूझकर टाला गया (बाद में देखेंगे, यूज़र के निर्देश पर)
- Launcher-conflict (Microsoft Launcher का स्वाइप-अप जेस्चर हमारे
  gesture handling से टकराना) — अभी भी टला हुआ है, v1.40 में हाथ नहीं
  लगाया
- app-list traversal में header पर रुक कर स्क्रॉल अटक जाना — v1.40 में
  इसका root-cause fix कोशिश की गई (देखें ऊपर scrollAtEdge/SCROLL_PENDING
  वाला हिस्सा), पर असली फ़ोन पर test होने तक इसे "पूरी तरह हल" नहीं माना
  जा सकता
- "In-world swipe gesture" आइटम

**v1.20 (यह वाला) — Per-app notification mute की UI (item #2):**
- नई `NotificationMuteSettingsActivity` — सभी launchable installed
  apps की list (`InstalledAppsPicker.loadLaunchableApps()` reuse
  किया, v1.19 वाला ही), हर एक के सामने checkbox — check करते ही वो
  app mute हो जाता है (`SettingsRepository.muteNotificationPackage`/
  `unmuteNotificationPackage`), uncheck करने पर वापस unmute
- ऊपर search box से नाम या package से list filter हो सकती है
- Detail Settings के Notifications section में नया बटन "Per-App
  Notification Mute" इसे खोलता है
- Backend (v1.4 से मौजूद) और यह UI अब पूरी तरह जुड़े — item #2 पूरा
- वही सीमा जो installed-apps picker में है: सिर्फ launcher-icon वाले
  apps दिखेंगे
- अभी तक real device पर test नहीं हुआ

**v1.19 — Per-app gesture scheme: Step 5 (installed-apps picker):**
- नई फाइल `settings/InstalledAppsPicker.kt` — reusable searchable
  picker dialog: सभी launchable installed apps की list (label +
  package name), ऊपर search box से नाम या package से filter कर सकते
  हैं, tap करने पर callback में चुना हुआ app मिलता है
- `AndroidManifest.xml` में `<queries>` block जोड़ा
  (ACTION_MAIN/CATEGORY_LAUNCHER) — यह Android 11+ की package
  visibility filtering को bypass करता है **बिना**
  `QUERY_ALL_PACKAGES` permission के, जो कि पहले जानबूझकर टाला गया था
  (Play Store पर declared-use justification चाहिए होता)। सीमा: सिर्फ
  launcher-icon वाले apps दिखेंगे, headless/system components नहीं —
  इसलिए `PerAppGestureSettingsActivity` में package-name EditText अब
  भी fallback के तौर पर मौजूद है
- `PerAppGestureSettingsActivity.kt` में नया "Choose installed app"
  बटन जोड़ा — picker से app चुनते ही package field अपने आप भर जाता है
  और gesture list load हो जाती है
- अभी तक real device पर test नहीं हुआ

**v1.18 — Quick-toggle/shortcut architecture: service को पूरी तरह बंद करना (item #5):**
- `MSScreenReaderService.disableServiceCompletely()` — Android के अपने
  public `disableSelf()` API (मौजूद है API 24 से, हमारे minSdk 26 के
  अंदर) को call करता है। यह वही करता है जो system Settings >
  Accessibility से service को off करना करता है — कोई घर का बनाया hack
  नहीं
- Main Menu में नया बटन "Disable screen reader completely" — दबाने पर
  पहले confirmation dialog आता है जो साफ़ बताता है कि यह one-way है:
  in-app से वापस on करने का कोई तरीका नहीं (service बंद होते ही पूरी
  class रुक जाती है) — वापस चालू करने के लिए या तो Settings या OS का
  volume-key shortcut / on-screen Accessibility Button चाहिए (अगर यह
  app उनका target है)
- यह "Suspend/Resume voice feedback" (सिर्फ आवाज़ रोकना, service चलती
  रहती है) से बिल्कुल अलग है — दोनों अब मौजूद हैं
- Item #5 (Quick-toggle/shortcut architecture) अब पूरी तरह पूरा —
  OS-level volume-key shortcut, on-screen Accessibility Button, और अब
  in-app पूरी तरह बंद करने का तरीका, तीनों मौजूद
- अभी तक real device पर test नहीं हुआ — खास तौर पर यह चेक करना ज़रूरी
  है कि `disableSelf()` के बाद वाकई Settings में toggle खुद-ब-खुद off
  दिखता है और OS shortcut से दोबारा on करने पर service ठीक से फिर शुरू
  होती है

**v1.17 — Per-app gesture scheme: Step 3 + Step 4 का बाकी हिस्सा (multi-finger gestures):**
- `MSScreenReaderService.kt` में नया `onGesture(AccessibilityGestureEvent)`
  override जोड़ा (API 33/Tiramisu+ only) — यही वो तरीका है जिससे Android
  2/3/4-finger swipe/tap gestures report करता है। पुराने single-finger
  `onGesture(Int)` की पूरी body को साझा `dispatchGesture(gestureId)`
  function में निकाला, दोनों overloads अब इसी को call करते हैं
- नतीजा: सभी 43 `GestureRegister` entries (सिर्फ 16 single-finger नहीं)
  अब असल finger gesture से fire होती हैं — बशर्ते person ने
  Default/Per-App Register Setting या gesture-launches-app से उन्हें
  कुछ assign किया हो। Multi-finger gestures का कोई hardcoded default
  नहीं है (`GestureManager.mapGesture()` उनके लिए हमेशा null देता है) —
  यह जानबूझकर है, यह उन्हीं registers का पूरा मतलब है कि वो सिर्फ
  customization के लिए हैं
- API 26-32 पर 2/3/4-finger gestures अब भी नहीं पहुंचते (platform की
  सीमा, `AccessibilityGestureEvent` क्लास ही API 33 में आई) —
  single-finger gestures हर supported API level (26+) पर पहले जैसे
  काम करते रहते हैं
- **ज़रूरी नोट**: दोनों onGesture overloads के double-fire ना होने का
  तर्क kdoc में लिखा गया है (framework का default forwarding
  व्यवहार), पर यह अभी सिर्फ Android की documented behavior पर आधारित
  है, code से खुद verify नहीं हुआ — real device पर टेस्ट करते वक्त
  खास ध्यान रखें कि कोई single-finger gesture डबल तो नहीं चल रहा (जैसे
  TOGGLE_SPEECH दो बार चलकर वापस unmute हो जाए)
- Per-app gesture scheme के अब सिर्फ Step 5 का बाकी हिस्सा बचा है
  (installed-apps picker — नीचे item #1 देखें); बाकी सब steps पूरे
  - अभी तक real device पर test नहीं हुआ

**v1.16 — Verbosity: बचे हुए 4 feasible items:**
- User ने असली TalkBack Verbosity screen की पूरी list फिर से भेजी;
  पहले से बचे 6 items में से 4 इस session में जोड़े, बाकी 2 (keyboard
  echo, pitch changes) architecture से बाहर रह गए (नीचे देखें)
- `SettingsRepository.kt` में 4 नए settings: `countRepeatedSymbolsEnabled`
  (default true), `speakTextFormattingEnabled` (default true),
  `speakNotificationsWhenScreenOffEnabled` (default false, TalkBack
  जैसा), `speakLettersWithExamplesEnabled` (default true)
- **Speak text formatting**: `NodeNavigator.textFormattingLabel()` —
  focused node के `text` (सिर्फ contentDescription नहीं) में अगर
  Spanned bold/italic/underline span मिले तो announceableDescription
  में जोड़ता है। सिर्फ वहीं काम करेगा जहां app असल में styled
  CharSequence भेजती है (ज़्यादातर plain TextView इसमें नहीं आते)
- **Count repeated symbols**: `NodeNavigator.collapseRepeatedSymbols()`
  — text में 4+ लगातार एक जैसे non-alphanumeric symbol (जैसे "----")
  को "4 dashes" जैसे count में बदल देता है, announceableDescription के
  text हिस्से पर लागू होता है
- **Speak notifications when screen off**: `NotificationReader.shouldRead()`
  में नया check — `PowerManager.isInteractive` से स्क्रीन off होने पर,
  अगर setting off हो (default), notification पढ़ी नहीं जाती
- **Speak letters with examples**: character granularity
  (`stepCharacter()`/`spokenForCharacter()`) में हर letter के साथ NATO
  phonetic जैसा example word (a-apple, b-bravo, ... h-hotel, ... — डॉक
  में दिया गया 'h, hotel' उदाहरण भी यही निकला) — capital prefix के साथ
  मिलकर बनता है जैसे "capital h, hotel"
- `VerbositySettingsActivity.kt` में 4 नए checkbox जोड़े (पुराने 7 के
  साथ, अब कुल 11 toggle + 1 punctuation picker)
- **जानबूझकर छोड़ा गया** (अभी भी architecture से बाहर):
  - Keyboard echo (on-screen/physical) — अलग IME hook चाहिए, बड़ा अलग
    feature
  - "Use pitch changes" (deleted letters higher pitch) — delete-key
    detection अभी हमारे पास नहीं है, keyboard echo जैसा ही बड़ा काम
- Verbosity feature अब TalkBack की मूल list के हिसाब से जितना feasible
  था उतना पूरी तरह cover हो चुका है। अभी तक real device पर test नहीं
  हुआ

**v1.15 (step 1/3) — Verbosity settings: data model + storage:**
- User ने TalkBack का असली "Verbosity" settings screen भेजा (screenshot
  से text) और पूछा कि क्या हमारे project में भी वैसा एक screen बनाया
  जाए (Reading Granularities screen जैसा pattern)। पूरी list छानकर
  तीन groups में बाँटा:
  - सीधे feasible (हमारे मौजूदा flat-node model पर, describe()/move()
    में wire हो सकते हैं): element type, container info (list/grid
    enter-exit), usage hints, list item count, window names, element
    IDs (unlabelled buttons के लिए), capital letters, punctuation level
  - ज़्यादा काम/अलग UI चाहिए: text formatting (bold/italic — spans check
    करना पड़ेगा), repeated-symbol counting, TalkBack जैसा speech
    rate/pitch slider, screen-off पर notifications
  - अभी architecture से बाहर: keyboard echo (अलग IME hook चाहिए),
    custom labels (अपना DB चाहिए), smart browse mode/table
    navigation/element description order (TalkBack की deep internals,
    हमारे simple model पर apply नहीं होतीं)
  - User ने पहले group (8 items) से शुरू करने को कहा, पूरा एक साथ पर
    थोड़ा-थोड़ा करके zip देते हुए
- `SettingsRepository.kt` में नया "Verbosity settings" section जोड़ा —
  8 नए settings: `speakElementTypeEnabled`, `speakContainerInfoEnabled`,
  `speakUsageHintsEnabled`, `speakListItemCountEnabled`,
  `speakWindowNamesEnabled`, `speakElementIdsEnabled`,
  `speakCapitalLettersEnabled` (सब default `true`, TalkBack के defaults
  जैसा), `speakListItemCountEnabled` (default `false`, TalkBack में भी
  OFF है), और `punctuationLevel` (String: "none"/"some"/"all", default
  "some")
**v1.15 (step 2/3) — Verbosity settings असल में wire किए:**
- `NodeNavigator.kt` में पुराने अकेले `describe()` को दो अलग functions
  में तोड़ा:
  - `rawText()` — सिर्फ plain text/contentDescription, बिना किसी
    decoration के। character/word/line stepping, clipboard copy, और
    remember-focus matching अब इसी को इस्तेमाल करते हैं (पहले remember-
    focus matching भी उसी combined describe() पर था, जो कि v1.15 के
    नए decorated version के साथ टूट जाता — अब ठीक से अलग रखा गया)
  - `announceableDescription()` — असल swipe-navigation announcement
    (moveNext/movePrevious/moveToFirst/moveToLast/moveNextListItem/
    movePreviousListItem सब इसी को कॉल करते हैं): rawText (या अगर टेक्स्ट
    बिल्कुल ना हो और `speakElementIdsEnabled` on हो तो resource-ID से
    बना fallback नाम), फिर element type (`speakElementTypeEnabled`),
    फिर usage hint (`speakUsageHintsEnabled` — clickable पर "double tap
    to activate", checkable पर "double tap to toggle", वगैरह), और सबसे
    आगे container transition की announcement
    (`speakContainerInfoEnabled` — list/grid में entering/exiting, साथ
    में `speakListItemCountEnabled` on हो तो item count) — यह हर बार
    नहीं, सिर्फ container असल में बदलने पर बोलता है
  - Character granularity (`stepCharacter()`) में दो नई चीज़ें जुड़ीं:
    uppercase letter पर "capital X" (`speakCapitalLettersEnabled`), और
    punctuation characters के लिए बोले जाने वाले शब्द
    (`punctuationLevel` — "none" में कुछ नहीं, "some" में सिर्फ common
    (. , ! ? : ;), "all" में extra symbols (- _ @ # $ % & * / \ ( ) ' ")
    भी शामिल)। Word/line stepping में कोई बदलाव नहीं किया — पूरे शब्द/
    लाइन normal तरीके से ही बोले जाते हैं, सिर्फ अकेले character पढ़ते
    वक़्त ही यह ज़रूरी होता है
  - `MSScreenReaderService.kt` में constructor order ठीक किया
    (`settings` अब `nodeNavigator` से पहले बनता है, ताकि उसे pass किया
    जा सके — CallHandlingManager जैसे बाकी classes के पैटर्न जैसा)
  - `speakFor()` में "Speak window names" (`speakWindowNamesEnabled`)
    गेट लगाया — सिर्फ `TYPE_WINDOW_STATE_CHANGED` की announcement को
    रोकता है, बाकी events (focus/click/वगैरह) उसी window के अंदर normal
    तरीके से बोलते रहते हैं
- अभी तक real device पर test नहीं हुआ

**v1.15 (step 3/3, यह session पूरी हुई) — Verbosity settings screen:**
- नई `VerbositySettingsActivity` (Reading Granularities/GranularitySettingsActivity
  जैसा pattern) — 7 checkbox (हर एक के नीचे छोटी सी description line):
  element type, container info, usage hints, list item count, window
  names, element IDs, capital letters — और punctuation के लिए तीन
  radio buttons (None/Some/All)। हर toggle सीधे `SettingsRepository` में
  लिखता है, कोई अलग "Save" बटन नहीं
- यह screen दो जगह से खुलती है: Main Menu (swipe up-then-right, Reading
  Granularities के ठीक बाद) और Detail Settings में नया "Verbosity"
  section (Reading Granularities section के ठीक बाद)
- `AndroidManifest.xml`, `strings.xml`, `activity_settings.xml` में
  ज़रूरी entries जोड़ी गईं
- Verbosity feature की तीनों steps (data model, wiring, UI) अब पूरी
  हो चुकी हैं। अभी तक real device पर test नहीं हुआ

**v1.14 — Accessibility volume अलग from music volume:**
- Google के official Android accessibility-service guide और TalkBack के
  खुद के AOSP source से confirm किया — screen reader की आवाज़ के लिए
  Android का खुद का अलग **STREAM_ACCESSIBILITY** audio stream होता है
  (API 26+, हमारा minSdk पहले से 26 है)। इसके लिए दो चीज़ें साथ चाहिए:
  1. `accessibility_service_config.xml` में
     `flagEnableAccessibilityVolume` flag (जोड़ दिया)
  2. जो भी आवाज़ यह service बजाए वो
     `AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY` के साथ बजे —
     `TtsManager` में TTS के लिए यह अब set कर दिया
     (`tts.setAudioAttributes(...)` in `onInit()`); earcons
     (`SoundSchemeManager`) में यह पहले से v1.2 से set था
- यह दोनों साथ होने पर Android **खुद-ब-खुद** यह तय करता है — जब भी यह
  service बोल रहा हो या earcon बजा रहा हो, उस वक़्त volume button दबाने
  पर सिर्फ accessibility volume बदलता है (system volume panel में एक
  अलग "Accessibility volume" slider दिखता है), और बाकी हर वक़्त volume
  button normal तरीके से music/ringer वगैरह control करता है — **कोई
  extra code नहीं लिखा** volume-key को intercept करके route करने के
  लिए, न ही कोई "slider पर focus रखो पहले" वाला अलग UI चाहिए — यह पूरी
  तरह platform की built-in behavior है जो सही audio attributes लगाते
  ही अपने आप काम करती है
- `onKeyEvent()` में जो पहले से call-answer के लिए volume key intercept
  होता है वो जस का तस है, उसमें कोई बदलाव नहीं किया — volume routing
  का यह नया feature उससे independent है
- अभी तक real device पर test नहीं हुआ (जैसे बाकी सब)

**v1.13 — Actions dispatch, Main Menu, Granularity settings, Remember Focus:**
- **Actions/gesture dispatch wired up** (single-finger only for अभी):
  `MSScreenReaderService.onGesture()` अब हर gesture पर पहले
  gesture-launches-app (कहीं से भी किसी app को खोलना) चेक करता है,
  फिर current foreground app के लिए per-app override, फिर global
  override, और आखिर में GestureManager की hardcoded default action।
  मतलब Default/Per-App Register Setting screens (v1.11 में सिर्फ data
  store करती थीं) अब असल में काम करती हैं — कोई भी single-finger
  gesture किसी भी app को खोलने या किसी भी built-in function
  ("Action") करने के लिए set किया जा सकता है
- **Main Menu**: swipe up-then-right अब `MainMenuActivity` खोलता है
  (screen reader menu) — इसमें Program Settings, Default/Per-App
  Register Setting, Reading Granularities, और Suspend/Resume voice
  feedback के shortcut हैं। पहले यह gesture TOGGLE_SPEECH पर था, वह
  अब swipe right-then-left (पहले unused reversal gesture) पर shift कर
  दिया — Accessibility Button अब भी सीधे TOGGLE_SPEECH करता है, कोई
  बदलाव नहीं
- **Reading Granularities settings screen**: नई
  `GranularitySettingsActivity` — v1.12 के 6 mode (Default, Character,
  Word, Line, List, Copy) में से हर एक के लिए checkbox, checked/
  unchecked list `SettingsRepository.enabledGranularities` में save
  होती है। Cycling gesture अब सिर्फ checked mode के बीच ही घूमता है
  (`MSScreenReaderService.activeGranularities()`/`cycleGranularity()`)।
  अगर सब कुछ uncheck कर दिया जाए तो automatically सिर्फ Default पर आ
  जाता है, कभी अटकता नहीं
- **Remember focus per app**: किसी app (जैसे WhatsApp) पर focus करके
  उसके अंदर गए, कुछ type किया, back आए — तो अगली बार उसी app पर वापस
  focus उसी element पर जाता है, शुरुआत से नहीं। `NodeNavigator` में
  `rememberFocus()`/`restoreRememberedFocus()` जोड़े (package name के
  हिसाब से last focused element का description save करके, वापस आने
  पर description मैच करके ढूंढता है)। दो नए toggle:
  `rememberFocusEnabled` (default on) और
  `readRememberedFocusOnReturn` (default on, restore होने पर बोला भी
  जाए या चुपचाप हो) — दोनों Detail Settings के नए "Focus Memory"
  section में
- **जानबूझकर छोड़ा गया**: multi-finger gestures (2-4 finger) अभी भी
  dispatch से नहीं जुड़े — यह पुराने deprecated `onGesture(Int)`
  overload से नहीं होता, नए `onGesture(AccessibilityGestureEvent)` की
  ज़रूरत है (item नीचे #3 जैसा), इस version में सिर्फ single-finger तक
  सीमित रखा। साथ ही remember-focus सिर्फ description से match करता है
  (कोई stable node ID नहीं) — अगर स्क्रीन का content पूरी तरह बदल जाए
  (जैसे नया message list पूरी तरह अलग दिखे) तो restore silently fail
  हो जाएगा, टूटेगा नहीं
- अभी तक real device पर test नहीं हुआ

**v1.12 — Reading granularity (swipe up-then-down / down-then-up):**
- नई फाइल `gestures/ReadingGranularity.kt` — 6 mode का cyclic enum:
  Default, Character, Word, Line, List, Copy। `next()`/`previous()`
  helper wrap-around के साथ (आखिरी से पहले वाले पर, पहले से आखिरी
  वाले पर)
- `GestureManager.kt`: पहले से मौजूद पर कभी इस्तेमाल न हुए दो
  "reversal" gestures wire किए — swipe up-then-down → अगला mode
  (`NEXT_GRANULARITY`), swipe down-then-up → पिछला mode
  (`PREVIOUS_GRANULARITY`)। बाकी दो reversal gestures (left-then-right,
  right-then-left) अभी भी unused हैं
- `MSScreenReaderService.kt`: mode बदलने पर TTS से mode का नाम बोला
  जाता है (कोई visual indicator नहीं है इसलिए) + नया
  `GRANULARITY_CHANGE` earcon। जब तक mode **Default** है, स्वाइप
  डाउन/अप पहले जैसा ही काम करते हैं (container scroll)। किसी और mode
  पर:
  - **Character/Word/Line**: फोकस किए गए element के टेक्स्ट में
    स्वाइप डाउन पर अगला, स्वाइप अप पर पिछला कैरेक्टर/शब्द/लाइन पढ़ा
    जाता है (टेक्स्ट के दोनों सिरों पर रुक जाता है, आगे-पीछे नहीं
    जाता)
  - **List**: स्वाइप डाउन/अप से सिर्फ list-जैसे container
    (ListView/RecyclerView/GridView) के अंदर वाले elements के बीच ही
    कूदा जाता है, बाकी elements छूट जाते हैं
  - **Copy**: स्वाइप डाउन पर फोकस किए गए element का टेक्स्ट clipboard
    पर copy होता है (पुराना accumulator साफ़ करके), स्वाइप अप पर उसी
    accumulator में append होता है (एक के बाद एक कई elements को जोड़कर
    clipboard पर रखा जा सकता है)
- `NodeNavigator.kt` में जोड़ा: `currentText()`, `resetSubNodeCursors()`,
  `stepCharacter()`/`stepWord()`/`stepLine()` (हर एक अपना cursor रखता
  है, focused node बदलने या mode बदलने पर reset होता है),
  `moveNextListItem()`/`movePreviousListItem()`
- `SoundEvent.kt` में तीन नए earcon जोड़े: `GRANULARITY_CHANGE`,
  `COPIED`, `APPENDED`
- **जानबूझकर छोड़ा गया**: mode persist नहीं होता — service दोबारा
  connect होने पर हमेशा Default से शुरू होता है, ताकि user किसी पुराने
  भूले हुए mode में फंसे नहीं। साथ ही character/word/line सिर्फ फोकस
  किए गए element के अंदर ही चलते हैं, element की सीमा पार करके अगले
  element में नहीं जाते — यह अगला कदम हो सकता है अगर चाहिए हो
- अभी तक real device पर test नहीं हुआ — item #6 नीचे देखें

**v1.11 — Detail Settings screen (Step 5 की शुरुआत):**
- नई **Detail Settings** screen (`SettingsActivity.kt` + `activity_settings.xml`),
  MainActivity से "Detail Settings" बटन से खुलती है। इसमें:
  - **Call Handling**: Caller Announcement, Answering Call (volume-button)
    टॉगल — पहले MainActivity पर थे, यहाँ move किए
  - **Accessibility Shortcut**: नया on/off टॉगल
    (`accessibilityShortcutEnabled`) — बंद करने पर on-screen
    Accessibility Button दबाने का कोई असर नहीं होगा। ज़रूरी नोट:
    volume-key-hold वाला असली "Accessibility Shortcut" पूरी तरह OS के
    हाथ में है (पूरी service on/off करता है) — इस टॉगल से सिर्फ
    on-screen button वाला suspend/resume behavior control होता है
  - **Sound**: Sound Scheme on/off टॉगल (backend पहले से था, अब UI मिली)
  - **Notifications**: Notification Reader on/off टॉगल (backend पहले
    से था, अब UI मिली)
  - **Gestures**: दो बटन — "Default Register Setting" और "Per-App
    Register Setting" (नीचे देखें) — साथ में "Reset all gesture
    customizations" बटन
  - **Power Button**: सिर्फ explanatory नोट (कोई टॉगल नहीं) — power
    button से call end करना platform limitation है, टॉगल देना झूठा
    भरोसा देता
- **Default Register Setting** (`DefaultGestureSettingsActivity.kt`) —
  सभी 43 registers की list (finger-count के हिसाब से grouped), हर एक
  के सामने dropdown जिससे उसकी global action बदली जा सकती है। नया
  storage: `SettingsRepository.getGlobalGestureOverride/setGlobalGestureOverride/clearGlobalGestureOverride`
- **Per-App Register Setting** (`PerAppGestureSettingsActivity.kt`) —
  package name टाइप करके load करो, फिर हर register के लिए या तो कोई
  action चुनो (उस app के अंदर ही लागू, v1.8 का override storage) या
  "Open this app instead" चेक करो (उस gesture से कहीं से भी वो app
  खुले, v1.9 का gesture-launches-app storage)
- **ज़रूरी सीमा**: ये तीनों screens सिर्फ safely data save करती हैं।
  असल gesture dispatch अभी भी GestureManager की hardcoded mapping और
  सिर्फ single-finger gestures पर चलता है — multi-finger detection
  (Step 3) और override-lookup को dispatch से जोड़ना (Step 4) अभी बाकी
  है, तभी यहाँ किए गए बदलाव असल में असर दिखाएंगे
- installed-apps picker के बजाय package-name टाइप करने वाला EditText
  रखा (QUERY_ALL_PACKAGES permission से बचने के लिए) — notification
  mute वाली per-app UI (item #2) में भी यही सीमा है, अभी दोनों जगह
  manual package name चाहिए

**v1.10 (यह वाला) — Per-app gesture scheme: Step 2 (foreground app tracking):**
- `MSScreenReaderService.kt` में `currentForegroundPackage` जोड़ा —
  हर `TYPE_WINDOW_STATE_CHANGED` event पर `event.packageName` से अपडेट
  होता है, `getCurrentForegroundPackage()` से पढ़ा जा सकता है
- अभी सिर्फ tracking है, कोई gesture-handling इसे पढ़ती नहीं — अगला
  step (#3/#4) multi-finger detection और override-lookup इसी पर बनेगा

**v1.9 — Gesture-launches-app (कोई भी app, किसी भी register पर, कहीं से भी):**
- `SettingsRepository.kt` में नया storage जोड़ा: किसी भी
  `GestureRegister` (1 से 4 finger, 43 में से कोई भी) को एक app खोलने
  के लिए असाइन किया जा सकता है — फ़ोन में कहीं भी हो, वो gesture करते
  ही वो app खुल जाएगा (per-app override से अलग — वो सिर्फ किसी app के
  अंदर रहते हुए काम करता है, यह कहीं से भी)
  - `getAllGestureAppLaunches()`, `getGestureAppLaunch(register)`,
    `setGestureAppLaunch(register, packageName)`,
    `clearGestureAppLaunch(register)`
  - एक register सिर्फ एक app खोल सकता है (single slot) — दोबारा सेट
    करने पर पुराना replace हो जाता है
- **अभी तक इस्तेमाल नहीं हो रहा** — v1.8 वाले per-app override जैसे
  यह भी अभी सिर्फ data + storage है। असल में gesture करने पर app
  खुलना, यह wiring आगे (जब multi-finger detection + override lookup
  जुड़ेगा) के साथ होगी।

**v1.8 — Per-app gesture scheme: Step 1 (data model + storage):**
- `GestureRegister.kt` (नई फाइल) — 1 से 4 finger तक Android जो भी
  gesture detect कर सकता है, उन सबकी पूरी list, एक enum में:
  - 1-finger: सीधे 4 + पहले से wired 8 L-shaped diagonal + 4 "reversal"
    (up-then-down, down-then-up, left-then-right, right-then-left) जो
    अभी तक इस्तेमाल नहीं हुए थे — कुल 16
  - 2-finger: 4 सीधे swipe + 5 tap variants (single/double/triple tap,
    double/triple-tap-hold) — कुल 9
  - 3-finger: 4 सीधे swipe + 6 tap variants (single/double/triple tap,
    single/double/triple-tap-hold) — कुल 10
  - 4-finger: 4 सीधे swipe + 4 tap variants (single/double/triple tap,
    double-tap-hold — इस platform पर single/triple-tap-hold मौजूद नहीं) — कुल 8
  - कुल मिलाकर 43 registers, हर एक के साथ असली Android gesture-id (int)
    और finger-count जुड़ा है, ताकि आगे UI/storage code इसे re-derive न
    करना पड़े
  - ज़रूरी नोट: diagonal/L-shaped combos (जैसे "down then right")
    सिर्फ 1-finger पर मौजूद हैं — 2/3/4-finger पर सिर्फ सीधे swipe
    (up/down/left/right) ही Android detect करता है
- `SettingsRepository.kt` में per-app override storage जोड़ा:
  `getAllAppGestureOverrides()`, `getAppGestureOverrides(packageName)`,
  `setAppGestureOverride(packageName, register, action)`,
  `clearAppGestureOverride(packageName, register)`,
  `clearAllAppGestureOverrides(packageName)` — डेटा एक StringSet में
  `"packageName::REGISTER::ACTION"` फॉर्मेट में save होता है (JSON
  library की ज़रूरत नहीं पड़ी, mutedNotificationPackages वाले pattern
  जैसा ही तरीका)
- **अभी तक इस्तेमाल नहीं हो रहा** — यह सिर्फ data model + storage है।
  ना तो foreground app track हो रहा है, ना multi-finger gestures असल
  में detect हो रहे हैं (अभी सिर्फ single-finger onGesture wired है),
  ना कोई UI बनी है इसे set करने के लिए। ये अगले steps में आएंगे
  (नीचे item #1 के sub-steps देखें)।
**v1.1:** TTS speech on focus/click/selection/window-change events।
**v1.2:** Custom sound-scheme folder।
**v1.3:** Single-finger swipe gestures (TalkBack-style navigation)।

**v1.4 (यह वाला) — Notification reading with filtering:**
- असली `MSNotificationListenerService` (`NotificationListenerService`)
  बनाई और manifest में register की — यह accessibility service से
  **अलग permission** मांगती है (Settings > Apps > Special app access >
  Notification access), MainActivity में इसके लिए सीधा बटन जोड़ा है
  जो system settings खोल देता है, और नीचे status दिखाता है कि access
  मिला है या नहीं (`NotificationManagerCompat.getEnabledListenerPackages`)।
- `NotificationReader` में असली filtering logic:
  - Master on/off toggle (`settings.notificationReaderEnabled`)
  - Per-app mute list (`settings.mutedNotificationPackages` +
    `muteNotificationPackage()`/`unmuteNotificationPackage()` helpers)
  - Group-summary notifications अपने आप skip होते हैं (double-announce
    से बचने के लिए)
  - Ongoing/foreground-service notifications (music playing, navigation,
    download progress) अपने आप skip होते हैं
- नोटिफिकेशन आने पर title+text TTS से बोला जाता है, साथ में नया
  `SoundEvent.NOTIFICATION` earcon बजता है (अगर user ने `notification.*`
  नाम की sound file अपने sound-scheme folder में रखी हो)।

**यह session — बाकी बचे 5 L-shaped gestures पूरे किए:**
- `GestureManager.kt` में सभी 8 L-shaped/diagonal gestures जोड़े:
  right+down → Quick Settings खोलना, down+left → Recent apps (overview),
  down+right → स्क्रीन के आखिरी element पर जाना ("to end"), up+left →
  स्क्रीन के पहले element पर जाना ("to top"), up+right → आवाज़
  suspend/resume करना (voice feedback toggle)।
- `NodeNavigator.kt` में `moveToFirst()` / `moveToLast()` जोड़े ताकि
  "to top"/"to end" gestures काम करें।
- `TtsManager.kt` में `toggleMute()`/`isMuted` जोड़ा — suspend होने पर
  तुरंत बोलना बंद हो जाता है, resume पर वापस चालू।
- `SoundEvent.kt` में `SPEECH_SUSPENDED`/`SPEECH_RESUMED` earcons जोड़े,
  क्योंकि suspend होने पर बोलकर confirm नहीं किया जा सकता।
- अभी तक इसे असली फोन पर test नहीं किया गया — item #6 नीचे देखें।

**यह session — Call handling (caller announcement + volume button से call उठाना):**
- `CallHandlingManager.kt` अब असली काम करता है (पहले सिर्फ खाली
  stub था, हमेशा `false` return करता था):
  - Call state track करता है (ringing / answered / ended)
  - Ringing पर TTS से "Incoming call" (नंबर मिला तो नंबर सहित) बोलता
    है — `settings.callerAnnouncerEnabled` से control होता है
  - Call खत्म होने पर उसकी duration बोलता है
  - Ringing के समय volume button दबाने पर call उठाता है
    (`TelecomManager.acceptRingingCall()`) — सिर्फ तब जब
    `settings.volumeAnswerEnabled` on हो
- `MSScreenReaderService.kt` में `onKeyEvent()` override करके volume-key
  press को intercept किया — सिर्फ ringing के दौरान और सेटिंग on होने
  पर ही event को consume करता है, बाकी समय normal volume control
  जस का तस काम करता है।
- `AndroidManifest.xml` में `ANSWER_PHONE_CALLS` permission जोड़ी
  (`READ_PHONE_STATE` पहले से थी)।
- `MainActivity.kt`/layout में जोड़ा: "Grant call permissions" बटन
  (runtime permission request), status text, और दो checkbox — call
  announcement on/off, volume-button-answer on/off — ताकि user बिना
  code छेड़े इन्हें चालू/बंद कर सके।
- **Power-button से call काटना जानबूझकर नहीं किया गया** — Android
  में `KEYCODE_POWER` एक system-reserved key है, यह किसी भी third-party
  accessibility service के `onKeyEvent` तक कभी नहीं पहुँचती (सिर्फ OS
  की अपनी built-in accessibility setting यह कर सकती है)। इसलिए
  `powerButtonEndCallSupported()` जानबूझकर हमेशा `false` return करता
  है — यह अधूरा feature नहीं, बल्कि platform की सीमा है।
- अभी तक real device पर test नहीं हुआ (permission dialog, actual
  incoming call व्यवहार, आदि)।

**यह session — OS-level shortcut support (item #5 का हिस्सा):**
- `accessibility_service_config.xml` में `canRequestAccessibilityButton="true"`
  जोड़ा — अब हमारी service navigation bar वाले on-screen "Accessibility
  Button" के लिए eligible है।
- `MSScreenReaderService.kt` में `onAccessibilityButtonClicked()` जोड़ा —
  दबाने पर वही voice suspend/resume होता है जो swipe up+right gesture
  से होता है (same `TOGGLE_SPEECH` logic reuse किया)।
- `MainActivity` में एक नया बटन जोड़ा जो सीधे system की Accessibility
  Settings खोल देता है, साथ में hint text कि वहां "MS Screen Reader"
  ढूंढकर उसकी Shortcut/Accessibility Button सेटिंग on करें।
- **ज़रूरी बात**: Android का असली "Accessibility Shortcut" (volume
  keys होल्ड करना) OS का generic feature है — किसी भी installed
  accessibility service के लिए अपने-आप काम करता है, इसके लिए हमें
  कोई extra code लिखने की ज़रूरत नहीं थी। बस सही जगह पहुँचना आसान
  बनाया।

## ⏳ अभी बाकी है (प्राथमिकता क्रम में)

1. **Per-app gesture scheme** (शुरू हो चुका है — sub-steps में)
   - ✅ Step 1 (v1.8): data model + storage (`GestureRegister.kt`,
     `SettingsRepository` में override storage)
   - ✅ Step 1b (v1.9): gesture-launches-app storage — कोई भी app किसी
     भी register पर, कहीं से भी खुलने के लिए असाइन हो सकता है
   - ✅ Step 2 (v1.10): foreground app tracking (`currentForegroundPackage`)
   - ✅ Step 5 शुरुआत (v1.11): Settings UI — Default Register Setting
     और Per-App Register Setting screens, दोनों काम कर रहे हैं data
     save करने के लिए, पर dispatch से अभी जुड़े नहीं (नीचे Step 3/4 देखें)
   - ✅ Step 3 (v1.17): multi-finger gestures असल में detect करना —
     `onGesture(AccessibilityGestureEvent)` override हो गया
   - ✅ Step 4 (v1.13 single-finger + v1.17 multi-finger): तीनों storage
     (per-app override, gesture-launches-app, global override) असल
     gesture-handling से जुड़ चुके, सभी finger-counts के लिए
   - ✅ Step 5 (v1.19): installed-apps picker — नया reusable
     `InstalledAppsPicker` (searchable dialog, `<queries>` manifest
     entry से QUERY_ALL_PACKAGES के बिना launchable apps की list),
     Per-App Register Setting screen में wire किया (manual package-name
     EditText अब भी fallback के तौर पर मौजूद है)
   - Item #1 (Per-app gesture scheme) अब **पूरी तरह पूरा** — data
     model, storage, foreground tracking, multi-finger detection,
     dispatch wiring, और अब installed-apps picker, सब कुछ
   - अभी तक real device पर test नहीं हुआ — installed-apps list Play
     Store/system apps जितनी लंबी हो सकती है, performance/scrolling
     असली फोन पर देखना ज़रूरी

2. ✅ **Per-app mute की UI** — पूरा (v1.20)
   - Backend (`mutedNotificationPackages`) + `NotificationMuteSettingsActivity`
     UI दोनों तैयार, Detail Settings से पहुंच सकते हैं

3. **बाकी Settings ↔ features का जुड़ाव**
   - `powerButtonEndCallEnabled` setting अभी भी save होता है पर उसका
     कोई असर नहीं है (और हो भी नहीं सकता — ऊपर platform-limitation
     वाला नोट देखें)। `callerAnnouncerEnabled`/`volumeAnswerEnabled`
     अब असल में काम करते हैं (इसी session में जोड़ा गया)।

4. **बाकी Settings UI**
   - sound-folder picker + notification-access बटन + call-permission
     बटन/toggles बन चुके हैं। बाकी toggles (power/volume-button end-call
     placeholder, sound-scheme on/off, notification-reader on/off) की
     UI अभी बाकी है।

5. ✅ **Quick-toggle / shortcut architecture** — पूरा (v1.18)
   - OS का built-in volume-key "Accessibility Shortcut" + on-screen
     "Accessibility Button" (voice suspend/resume), और अब Main Menu से
     `disableSelf()` से service को पूरी तरह बंद करना — तीनों मौजूद

6. **सभी gestures + call handling की real-device टेस्टिंग**
   - v1.3-v1.6 में लिखे गए, असली फोन पर verify नहीं हुआ — खासकर
     incoming call पर announcement, permission dialogs, volume-key
     answer असली ringing call पर।

7. ✅ **4-finger swipe up/down = Window Navigation** — पूरा (v1.35)
   - नया `WindowNavigator` (gestures package): सिर्फ़ TalkBack जैसी 3
     window types में घूमता है — status/notification bar
     (`TYPE_SYSTEM`), main app content (`TYPE_APPLICATION`), keyboard
     (`TYPE_INPUT_METHOD`) — बाकी (overlay, split-screen divider वगैरह)
     छोड़ देता है; जो window आए उसके अंदर पहला focusable node ढूंढ कर
     accessibility focus देता है, और window का title (अगर platform
     देता है) या generic label ("Keyboard window" / "App window" /
     "System window") बोलता है
   - "अभी active कौन सी window है" पता करने के लिए हर window का अपना
     `isFocused()` चेक करता है (v1.34 में `rootInActiveWindow` के id से
     मैच किया जा रहा था, जो सिर्फ़ app window के लिए सही होता, status
     bar/keyboard के लिए नहीं — इसी session में सुधारा गया)
   - `GestureManager.mapGesture()` में default: 4-finger swipe down →
     अगली window, swipe up → पिछली window
   - `TYPE_WINDOW_STATE_CHANGED` पर `windowNavigator.reset()` कॉल होता
     है ताकि हर असली window-change के बाद अगला swipe सही जगह से शुरू हो
   - असली TalkBack का internal private source इस session में access
     नहीं हुआ (google/talkback की सब-फोल्डर्स GitHub पर robots.txt से
     ब्लॉक थीं) — documented public Android API
     (`getWindows`/`AccessibilityWindowInfo`) और TalkBack की अपनी
     help-page (जो 3-तरह की windows के बीच cycle होने की बात बताती है)
     के आधार पर बनाया है
   - अभी बाकी (item #6 जैसे): असली फोन पर टेस्ट नहीं हुआ — खासकर
     split-screen और keyboard के बीच स्विच करने पर

8. ✅ **Microsoft Launcher वाला "scrolling stuck" bug** — पूरा (v1.36)
   - जड़ (root cause) मिली: `MSScreenReaderService` अपनी `handledEventTypes`
     list में `TYPE_WINDOW_CONTENT_CHANGED` शामिल ही नहीं करता था — यानी
     जब कोई RecyclerView/GridView (जैसे app drawer) scroll होने पर अपने
     child views recycle करती है, तो `NodeNavigator` की `flatNodes` list
     कभी update ही नहीं होती थी। इसी वजह से लंबी list में स्वाइप करते
     वक़्त शुरुआती कुछ items के बाद navigation "अटक" जाता था — असली
     screen पर आगे items होते हुए भी हमारी list में वो कभी जुड़ते नहीं थे
   - अब `TYPE_WINDOW_CONTENT_CHANGED` (सिर्फ़ `CONTENT_CHANGE_TYPE_SUBTREE`
     flag वाले events पर) `nodeNavigator.refresh()` कॉल करता है, 250ms
     throttle के साथ (एक ही scroll से कई events आते हैं, हर एक पर पूरा
     tree दोबारा बनाना बर्बादी होती)
   - `NodeNavigator.refresh()` भी सुधारा: पहले हर refresh पर
     `currentIndex` को -1 पर reset कर देता था, जो content-change वाले
     बार-बार refresh में हर बार "शुरुआत पर वापस" जैसा महसूस होता। अब
     refresh से पहले जो node focused था उसका text याद रखता है, नई list
     में वही text ढूंढ कर वहीं currentIndex सेट करता है — genuine window
     change (दूसरी app खुलना) पर वैसे भी कोई match नहीं मिलेगा तो पुराना
     -1 वाला व्यवहार खुद ही बना रहता है
   - **इस session में हल नहीं हुआ**: "double-tap से Apps button नहीं
     खुलता" वाला हिस्सा अलग समस्या है — double-tap-to-click टच-एक्सप्लोरेशन
     में सीधे Android OS द्वारा हैंडल होता है, हमारे अपने कोड में
     `GestureAction.ACTIVATE` को कोई gesture कभी trigger ही नहीं करता
     (कोड में dead branch है)। मतलब यह घटना हमारे कोड तक पहुंचती ही नहीं
     — असली डिवाइस पर टेस्ट किए बिना अंदाज़े से "fix" करना जोखिम भरा है,
     इसलिए इसे अलग item के तौर पर छोड़ा है

9. **नोट: nav-bar button label वाला कोड पहले से सही है, नहीं बदला**
   - Document 40 का दावा था कि TalkBack हमेशा नोड की मूल resource-ID
     ("back"/"recent_apps") के हिसाब से बोलता है, स्क्रीन पोज़ीशन या live
     label को नज़रअंदाज़ करके — लेकिन यह सीधे उस बात से टकराता है जो आपने
     खुद टेस्ट करके बताया था: असली TalkBack आपकी custom swap (Back ↔
     Overview) को सही पहचानता है। हमारा मौजूदा `navigationBarButtonLabel()`
     पहले से resource-ID से बटन ढूंढता है पर बोलने के लिए system का दिया
     हुआ live label (contentDescription) पहले लेता है, ID-based नाम को
     सिर्फ़ तभी इस्तेमाल करता है जब live label न मिले — यही व्यवहार आपके
     बताए असली TalkBack से मैच करता है। इसलिए यहां Document 40 की बात को
     लागू नहीं किया — वो उल्टा वही bug वापस ले आती जो पहले से ठीक है

## 🔭 Future (मूल blueprint से)
- Advanced/custom gestures, OCR, AI assistant, plugins, profiles,
  backup/restore, Braille।

## अगले सेशन/अकाउंट पर काम शुरू करने का तरीका
यह folder unzip करके नए session में अपलोड करें और बताएं जैसे:
"REMAINING_WORK.md के item #2 (settings-service जुड़ाव) पर काम करो"



