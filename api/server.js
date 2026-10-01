import express from "express";
import { MongoClient } from "mongodb";

const { MONGO_URL = "mongodb://localhost:27017/halluzinationen", PORT = 3000 } = process.env;

const client = new MongoClient(MONGO_URL);
await client.connect();
const products = client.db().collection("products");

const app = express();

app.get("/api/health", (_req, res) => res.json({ ok: true }));

app.get("/api/products", async (_req, res) => {
  const docs = await products.find({}, { projection: { _id: 0 } }).toArray();
  res.json(docs);
});

app.listen(PORT, () => console.log(`API läuft auf Port ${PORT}`));
