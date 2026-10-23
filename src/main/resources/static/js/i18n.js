// Language switch for the storefront: English, Hindi, Telugu.
//
// How it works: the page is written in English. The dictionary below maps an exact English text (a label, a placeholder, a
// button) to its translation, and this script swaps matching text and placeholder/aria-label/title attributes in place - also
// for content the page builds later, via a MutationObserver. Anything not in the dictionary simply stays English, so a
// missing translation is never a broken page. The chosen language is remembered in this browser.
//
// The Hindi and Telugu texts are written by an AI assistant and have not been checked by a native speaker yet; please
// have someone review them before relying on them. To add a string, add the same English key to BOTH languages (a test
// checks that the two lists stay in step).
(function () {
  "use strict";

  const LANGUAGES = { en: "English", hi: "हिन्दी", te: "తెలుగు" };

  const DICTIONARY = {
    hi: {
      "Fresh picks, fast checkout": "ताज़ा चुनाव, तेज़ चेकआउट",
      "Welcome": "स्वागत है",
      "Enter your phone number and email. We'll email you a 6-digit sign-in code.": "अपना फ़ोन नंबर और ईमेल दर्ज करें। हम आपको 6 अंकों का साइन-इन कोड ईमेल करेंगे।",
      "10-digit phone number": "10 अंकों का फ़ोन नंबर",
      "Email address": "ईमेल पता",
      "Email me a code": "मुझे कोड ईमेल करें",
      "6-digit code": "6 अंकों का कोड",
      "Sign in": "साइन इन करें",
      "Use a different number": "दूसरा नंबर इस्तेमाल करें",
      "Track an order instead": "इसके बजाय ऑर्डर ट्रैक करें",
      "Track an order": "ऑर्डर ट्रैक करें",
      "Track order": "ऑर्डर ट्रैक करें",
      "Order ID": "ऑर्डर आईडी",
      "Back to login": "लॉगिन पर वापस जाएँ",
      "Go to the shop": "दुकान पर जाएँ",
      "Order status": "ऑर्डर की स्थिति",
      "Welcome back to Charan Mart": "चरण मार्ट में फिर से स्वागत है",
      "Search products": "उत्पाद खोजें",
      "Search products (press / )": "उत्पाद खोजें ( / दबाएँ)",
      "Sort: Default": "क्रम: डिफ़ॉल्ट",
      "Price: low to high": "कीमत: कम से ज़्यादा",
      "Price: high to low": "कीमत: ज़्यादा से कम",
      "Top rated": "सबसे ज़्यादा रेटिंग वाले",
      "Name A-Z": "नाम A-Z",
      "Min ₹": "न्यूनतम ₹",
      "Max ₹": "अधिकतम ₹",
      "All products": "सभी उत्पाद",
      "New": "नया",
      "On sale (": "ऑफ़र में (",
      "Wishlist (": "इच्छा-सूची (",
      "Recently viewed": "हाल ही में देखे गए",
      "My account": "मेरा खाता",
      "My notifications": "मेरी सूचनाएँ",
      "Help": "सहायता",
      "Your cart": "आपकी कार्ट",
      "Your cart is empty.": "आपकी कार्ट खाली है।",
      "Share cart": "कार्ट शेयर करें",
      "Total": "कुल",
      "Delivery": "डिलीवरी",
      "Bundle savings": "बंडल बचत",
      "No saved address selected": "कोई सहेजा हुआ पता नहीं चुना गया",
      "Delivery time: any": "डिलीवरी का समय: कोई भी",
      "Morning (9am-12pm)": "सुबह (9 से 12 बजे)",
      "Afternoon (12pm-4pm)": "दोपहर (12 से 4 बजे)",
      "Evening (4pm-8pm)": "शाम (4 से 8 बजे)",
      "Delivery instructions (optional)": "डिलीवरी निर्देश (वैकल्पिक)",
      "Gift message for the card (optional)": "कार्ड के लिए उपहार संदेश (वैकल्पिक)",
      "Coupon code (optional)": "कूपन कोड (वैकल्पिक)",
      "Loyalty points to redeem (optional)": "भुनाने के लिए लॉयल्टी पॉइंट (वैकल्पिक)",
      "Place order": "ऑर्डर करें",
      "PhonePe phone number": "फ़ोनपे फ़ोन नंबर",
      "PhonePe PIN": "फ़ोनपे पिन",
      "Forgot PIN?": "पिन भूल गए?",
      "Compare": "तुलना करें",
      "Clear": "साफ़ करें",
      "Close": "बंद करें",
      "Compare products": "उत्पादों की तुलना करें",
      "Copy link": "लिंक कॉपी करें",
      "Delivery to": "डिलीवरी पिनकोड",
      "Pincode": "पिनकोड",
      "Check": "जाँचें",
      "Frequently bought together": "अक्सर साथ में खरीदे गए",
      "Questions & answers": "प्रश्न और उत्तर",
      "Ask a question about this product": "इस उत्पाद के बारे में प्रश्न पूछें",
      "Ask": "पूछें",
      "Reviews": "समीक्षाएँ",
      "Sort by": "क्रमबद्ध करें",
      "Newest": "नवीनतम",
      "Most helpful": "सबसे उपयोगी",
      "Highest rated": "सबसे ऊँची रेटिंग",
      "Lowest rated": "सबसे कम रेटिंग",
      "Write a review": "समीक्षा लिखें",
      "Your name": "आपका नाम",
      "Your comment": "आपकी टिप्पणी",
      "Submit review": "समीक्षा भेजें",
      "Add to cart": "कार्ट में जोड़ें",
      "Out of stock": "स्टॉक में नहीं है",
      "No reviews yet": "अभी कोई समीक्षा नहीं",
      "Language": "भाषा"
    },
    te: {
      "Fresh picks, fast checkout": "తాజా ఎంపికలు, వేగవంతమైన చెక్‌అవుట్",
      "Welcome": "స్వాగతం",
      "Enter your phone number and email. We'll email you a 6-digit sign-in code.": "మీ ఫోన్ నంబర్ మరియు ఇమెయిల్ ఇవ్వండి. మేము మీకు 6 అంకెల సైన్-ఇన్ కోడ్‌ను ఇమెయిల్ చేస్తాము.",
      "10-digit phone number": "10 అంకెల ఫోన్ నంబర్",
      "Email address": "ఇమెయిల్ చిరునామా",
      "Email me a code": "నాకు కోడ్ ఇమెయిల్ చేయండి",
      "6-digit code": "6 అంకెల కోడ్",
      "Sign in": "సైన్ ఇన్",
      "Use a different number": "వేరే నంబర్ ఉపయోగించండి",
      "Track an order instead": "బదులుగా ఆర్డర్‌ను ట్రాక్ చేయండి",
      "Track an order": "ఆర్డర్‌ను ట్రాక్ చేయండి",
      "Track order": "ఆర్డర్ ట్రాక్ చేయండి",
      "Order ID": "ఆర్డర్ ID",
      "Back to login": "లాగిన్‌కు తిరిగి వెళ్ళండి",
      "Go to the shop": "షాప్‌కు వెళ్ళండి",
      "Order status": "ఆర్డర్ స్థితి",
      "Welcome back to Charan Mart": "చరణ్ మార్ట్‌కు మళ్ళీ స్వాగతం",
      "Search products": "ఉత్పత్తులను వెతకండి",
      "Search products (press / )": "ఉత్పత్తులను వెతకండి ( / నొక్కండి)",
      "Sort: Default": "క్రమం: డిఫాల్ట్",
      "Price: low to high": "ధర: తక్కువ నుండి ఎక్కువ",
      "Price: high to low": "ధర: ఎక్కువ నుండి తక్కువ",
      "Top rated": "అత్యధిక రేటింగ్ ఉన్నవి",
      "Name A-Z": "పేరు A-Z",
      "Min ₹": "కనిష్ట ₹",
      "Max ₹": "గరిష్ట ₹",
      "All products": "అన్ని ఉత్పత్తులు",
      "New": "కొత్త",
      "On sale (": "ఆఫర్‌లో (",
      "Wishlist (": "విష్‌లిస్ట్ (",
      "Recently viewed": "ఇటీవల చూసినవి",
      "My account": "నా ఖాతా",
      "My notifications": "నా నోటిఫికేషన్లు",
      "Help": "సహాయం",
      "Your cart": "మీ కార్ట్",
      "Your cart is empty.": "మీ కార్ట్ ఖాళీగా ఉంది.",
      "Share cart": "కార్ట్‌ను షేర్ చేయండి",
      "Total": "మొత్తం",
      "Delivery": "డెలివరీ",
      "Bundle savings": "బండిల్ పొదుపు",
      "No saved address selected": "సేవ్ చేసిన చిరునామా ఎంచుకోలేదు",
      "Delivery time: any": "డెలివరీ సమయం: ఏదైనా",
      "Morning (9am-12pm)": "ఉదయం (9 నుండి 12 వరకు)",
      "Afternoon (12pm-4pm)": "మధ్యాహ్నం (12 నుండి 4 వరకు)",
      "Evening (4pm-8pm)": "సాయంత్రం (4 నుండి 8 వరకు)",
      "Delivery instructions (optional)": "డెలివరీ సూచనలు (ఐచ్ఛికం)",
      "Gift message for the card (optional)": "కార్డ్ కోసం బహుమతి సందేశం (ఐచ్ఛికం)",
      "Coupon code (optional)": "కూపన్ కోడ్ (ఐచ్ఛికం)",
      "Loyalty points to redeem (optional)": "రీడీమ్ చేయాల్సిన లాయల్టీ పాయింట్లు (ఐచ్ఛికం)",
      "Place order": "ఆర్డర్ చేయండి",
      "PhonePe phone number": "ఫోన్‌పే ఫోన్ నంబర్",
      "PhonePe PIN": "ఫోన్‌పే పిన్",
      "Forgot PIN?": "పిన్ మర్చిపోయారా?",
      "Compare": "పోల్చండి",
      "Clear": "క్లియర్ చేయండి",
      "Close": "మూసివేయండి",
      "Compare products": "ఉత్పత్తులను పోల్చండి",
      "Copy link": "లింక్ కాపీ చేయండి",
      "Delivery to": "డెలివరీ పిన్‌కోడ్",
      "Pincode": "పిన్‌కోడ్",
      "Check": "తనిఖీ చేయండి",
      "Frequently bought together": "తరచుగా కలిసి కొనేవి",
      "Questions & answers": "ప్రశ్నలు & సమాధానాలు",
      "Ask a question about this product": "ఈ ఉత్పత్తి గురించి ప్రశ్న అడగండి",
      "Ask": "అడగండి",
      "Reviews": "సమీక్షలు",
      "Sort by": "క్రమబద్ధీకరించండి",
      "Newest": "కొత్తవి",
      "Most helpful": "అత్యంత ఉపయోగకరమైనవి",
      "Highest rated": "అత్యధిక రేటింగ్",
      "Lowest rated": "అత్యల్ప రేటింగ్",
      "Write a review": "సమీక్ష రాయండి",
      "Your name": "మీ పేరు",
      "Your comment": "మీ వ్యాఖ్య",
      "Submit review": "సమీక్షను సమర్పించండి",
      "Add to cart": "కార్ట్‌కు జోడించండి",
      "Out of stock": "స్టాక్ లేదు",
      "No reviews yet": "ఇంకా సమీక్షలు లేవు",
      "Language": "భాష"
    }
  };

  const ATTRIBUTES = ["placeholder", "aria-label", "title"];
  const SKIP_TAGS = new Set(["SCRIPT", "STYLE", "NOSCRIPT", "TEXTAREA", "CODE", "PRE"]);

  let language = "en";
  try {
    const saved = localStorage.getItem("shopLanguage");
    if (saved && LANGUAGES[saved]) language = saved;
  } catch (e) { /* storage blocked: English each visit */ }

  // For each text node / attribute we changed: what the page originally had and what we put there, so a switch back to
  // English restores it and a value the page rewrites itself is recognised as new original text.
  const textState = new WeakMap();
  const attrState = new WeakMap();

  function translate(text) {
    const table = DICTIONARY[language];
    if (!table) return text;
    const key = text.trim();
    const hit = key && Object.prototype.hasOwnProperty.call(table, key) ? table[key] : null;
    return hit === null ? text : text.replace(key, hit);
  }

  function doText(node) {
    const state = textState.get(node);
    if (state && state.shown === node.nodeValue) {
      // our own earlier output: just re-derive from the original (language may have changed)
      const shown = translate(state.original);
      if (shown !== state.shown) { node.nodeValue = shown; state.shown = shown; }
      return;
    }
    const original = node.nodeValue;
    const shown = translate(original);
    textState.set(node, { original, shown });
    if (shown !== original) node.nodeValue = shown;
  }

  function doAttributes(element) {
    let states = attrState.get(element);
    if (!states) { states = {}; attrState.set(element, states); }
    for (const name of ATTRIBUTES) {
      if (!element.hasAttribute(name)) continue;
      const current = element.getAttribute(name);
      const state = states[name];
      let original = current;
      if (state && state.shown === current) original = state.original;
      const shown = translate(original);
      states[name] = { original, shown };
      if (shown !== current) element.setAttribute(name, shown);
    }
  }

  function walk(root) {
    if (root.nodeType === Node.TEXT_NODE) {
      if (root.parentNode && !SKIP_TAGS.has(root.parentNode.nodeName)) doText(root);
      return;
    }
    if (root.nodeType !== Node.ELEMENT_NODE || SKIP_TAGS.has(root.nodeName) || root.hasAttribute("data-no-i18n")) return;
    doAttributes(root);
    for (const child of root.childNodes) walk(child);
  }

  let observer = null;
  function startObserving() {
    observer = new MutationObserver((mutations) => {
      observer.disconnect();
      try {
        for (const m of mutations) {
          if (m.type === "characterData") walk(m.target);
          else if (m.type === "attributes") doAttributes(m.target);
          else m.addedNodes.forEach(walk);
        }
      } finally {
        observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
      }
    });
    observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
  }

  function setLanguage(next) {
    if (!LANGUAGES[next]) return;
    language = next;
    try { localStorage.setItem("shopLanguage", next); } catch (e) { /* not remembered */ }
    document.documentElement.lang = next;
    if (observer) observer.disconnect();
    walk(document.body);
    if (observer) observer.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRIBUTES });
    const select = document.getElementById("langSelect");
    if (select) select.value = next;
  }

  function init() {
    const select = document.getElementById("langSelect");
    if (select) {
      select.innerHTML = Object.entries(LANGUAGES).map(([code, name]) => `<option value="${code}">${name}</option>`).join("");
      select.value = language;
      select.addEventListener("change", () => setLanguage(select.value));
    }
    document.documentElement.lang = language;
    walk(document.body);
    startObserving();
  }

  window.shopI18n = { setLanguage, current: () => language, languages: () => ({ ...LANGUAGES }), dictionary: DICTIONARY };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();
