# Page Modrinth — à copier/coller

## Métadonnées du projet
- **Type** : Mod · **Environnement** : Client uniquement
- **Catégories** : Library, Utility
- **Licence** : MIT
- **Loaders** : Fabric · **Versions MC** : 1.21.11
- **Dépendance requise** : Fabric API
- **Liens** : Source = `https://github.com/Ostores/ultralight-fabric` · Wiki = `…/wiki` · Issues = `…/issues`
- **Icône** : `src/main/resources/assets/ultralight/icon.png` (ou ta propre image 512×512)
- ⚠️ *« Ultralight » est une marque d'Ultralight Inc. Si tu veux éviter toute ambiguïté, nomme le
  projet p.ex. « Ultralight Web (Fabric) » et précise « powered by Ultralight ».*

## Résumé (summary, ≤256 car.)
> Rendu HTML/CSS/JS léger dans Minecraft via Ultralight 1.4 (WebKit 615). Mod-API Fabric pour des UIs/overlays web in-game — alternative légère à Chromium.

## Description (body)

# Ultralight for Fabric

Un **mod-API** qui permet aux autres mods d'afficher du **HTML / CSS / JS** dans Minecraft,
rendu par le moteur **[Ultralight](https://ultralig.ht) 1.4 (WebKit 615 ≈ Safari 16.4)**.

Pensé comme une alternative **légère** (faible empreinte mémoire) à une solution Chromium —
idéal pour des **overlays / interfaces web in-game**.

> ℹ️ **C'est un mod-API** : seul, il n'ajoute rien de visible. Il est utilisé comme **dépendance**
> par d'autres mods.

## ✨ Fonctionnalités
- Rendu **HTML/CSS/JS moderne** (grid, flexbox `gap`, `aspect-ratio`, `clip-path`,
  `-webkit-backdrop-filter`, variables CSS, transitions…).
- **Pont JS ↔ Java** (`window.ulQuery`) pour la communication page ↔ mod.
- **Input** complet : souris, clavier, molette, focus, curseurs.
- Rendu dans une **texture Minecraft** (intégrable au HUD ou dans un écran).

## 📦 Comment ça marche
Le jar est **léger** : au **premier lancement**, le mod télécharge automatiquement le moteur natif
correspondant à ton OS (Windows / Linux / macOS Apple Silicon) et le met en cache. **Une connexion
internet est requise au premier démarrage.**

## ✅ Prérequis
- Minecraft **1.21.11** + **Fabric Loader** + **Fabric API**
- Windows x64, Linux x64, ou macOS arm64 *(Intel mac : pas encore supporté)*

## 📄 Licences & crédits
- Code du mod : **MIT**.
- Moteur **Ultralight** © Ultralight, Inc. — utilisé sous [licence Free](https://ultralig.ht/pricing)
  (binaires téléchargés au runtime depuis leur format de distribution).
- Binding **[Luminescence](https://github.com/Solomon-Team/Luminescence)** (LGPL-3.0) · **icu4j** (Unicode).

---

## Checklist de publication
1. Construire le jar : `./gradlew build` → `build/libs/ultralight-1.0.jar` (~14 Mo).
2. Sur Modrinth : *Create a project* → remplir les métadonnées ci-dessus → uploader l'icône.
3. *Create version* → uploader le jar → loader **Fabric**, MC **1.21.11**, dépendance **Fabric API (required)**.
4. Publier. (Optionnel : automatiser via le plugin Gradle **Minotaur** avec un token Modrinth.)
