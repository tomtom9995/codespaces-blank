// Wird nur beim allerersten Start (leeres Volume) ausgeführt.
db = db.getSiblingDB("halluzinationen");
db.products.insertMany([
  {
    name: "iPhone 27 Pro Max Ultra",
    tagline: "Jetzt mit Kamera, die Fotos von morgen macht.",
    detail: "Der A31-Chip sagt voraus, was du fotografieren willst – und hat es schon getan.",
    price: "ab 2.999 € oder eine Niere",
    confidence: 0.97,
  },
  {
    name: "Vision Pro Air Contact",
    tagline: "Spatial Computing. Jetzt als Kontaktlinse.",
    detail: "Blinzle zweimal, um eine Keynote zu starten. Blinzle dreimal, um sie nie wieder zu beenden.",
    price: "ab 7.499 €",
    confidence: 0.94,
  },
  {
    name: "MacBook Fold Infinity",
    tagline: "So dünn, dass es nur eine Seite hat.",
    detail: "Bis zu 47 Stunden Akkulaufzeit – gemessen in einem Paralleluniversum.",
    price: "ab 3.299 €",
    confidence: 0.99,
  },
  {
    name: "AirPods Mind",
    tagline: "Hör, was du denkst. Bevor du es denkst.",
    detail: "Adaptive Gedankenunterdrückung blendet störende Ideen in Meetings automatisch aus.",
    price: "ab 349 €",
    confidence: 0.91,
  },
  {
    name: "Apple Toaster (Classic)",
    tagline: "1984 erfunden. Heute neu erfunden. Toastet in Retina.",
    detail: "Steve Jobs hat ihn angeblich persönlich entworfen. Quelle: vertrau mir.",
    price: "ab 1.199 €",
    confidence: 1.0,
  },
]);
