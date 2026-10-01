import { useEffect, useState } from "react";

export default function App() {
  const [products, setProducts] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    fetch("/api/products")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then(setProducts)
      .catch((e) => setError(e.message));
  }, []);

  return (
    <>
      <nav className="nav">
        <span className="logo"></span>
        <span>Store</span><span>Mac</span><span>iPhone</span><span>Vision</span><span>Toaster</span>
      </nav>

      <header className="hero">
        <p className="eyebrow">Hello World</p>
        <h1>Halluziniert. Revolutionär.</h1>
        <p className="sub">Produkte, die es nie gab – mit 100 % Überzeugung präsentiert von einer KI.</p>
        <div className="ctas">
          <a href="#produkte">Mehr erfahren ›</a>
          <a href="#produkte">Kaufen ›</a>
        </div>
      </header>

      <main id="produkte">
        {error && <p className="error">API nicht erreichbar: {error}</p>}
        {!error && products.length === 0 && <p className="loading">Halluziniere …</p>}
        {products.map((p, i) => (
          <section key={p.name} className={`tile ${i % 2 ? "dark" : ""}`}>
            <h2>{p.name}</h2>
            <p className="tagline">{p.tagline}</p>
            <p className="detail">{p.detail}</p>
            <p className="price">{p.price}</p>
            <p className="confidence">KI-Konfidenz: {Math.round(p.confidence * 100)} %</p>
          </section>
        ))}
      </main>

      <footer>
        Parodie / Demo. Alle Produkte sind KI-Halluzinationen und frei erfunden.
        Nicht mit Apple Inc. verbunden. Daten aus MongoDB.
      </footer>
    </>
  );
}
