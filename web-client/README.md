# Guess Market — Web Client (Exercise 4 Bonus)

A React + Vite web client for the Guess Market server, submitted separately from Exercise 3.
It runs against the **same, unchanged** Tomcat server/WAR as the JavaFX client — no different
way to start or configure Tomcat is needed.

Scope (per the Exercise 4 spec section): a login screen, a **read-only** Events screen, and a
"My Account" screen (own balance, deposit funds, transaction ledger). File upload, chat, and
creating/opening/closing/trading events are **not** part of this client — those stay JavaFX-only.

## How to run it (grader instructions)

1. Start the Guess Market server exactly as for Exercise 3 (deploy `GuessMarket.war` to Tomcat
   and start Tomcat — see the main project README/submission notes for that step; this client
   does not change it).
2. From this project's root folder, run **`run-web-client.bat`**. It runs `npm install` and then
   `npm run dev` — no other tool installation happens automatically.
3. Once the dev server starts, open the URL it prints in a terminal:

   **http://localhost:5173**

   in a browser. Log in with any username (no password) and use the Events / My Account tabs.

**The Events screen shows only events already uploaded to the server.** File upload is out of
scope for this client (per the spec). Before there is anything to see on the Events screen, launch
the already-submitted Exercise 3 JavaFX client (`run-client.bat`), log in there, and upload a real
events file (e.g. one of `test_files/*.xml`). This is the spec's own intended workflow — the web
client sees whatever JavaFX clients have uploaded to the shared server, not a separate data source.

The web client (`http://localhost:5173`) and the server (`http://localhost:8080`) are
deliberately different origins — the server has a small CORS filter
(`server/src/server/CorsFilter.java`) added specifically so this client's credentialed
cross-origin requests are allowed; nothing else about the server changed.

## What the `.bat` file does

`run-web-client.bat` changes into `web-client/`, runs `npm install` to fetch React/Vite and
their dependencies into `web-client/node_modules/` (not committed), then runs `npm run dev` to
start the Vite dev server on port 5173. It assumes only Node.js/npm are already installed on the
grading machine — nothing else.

## AI Workflow

*(To be filled in personally — not written by the AI.)*

- Which AI tool was used, and why?
- Were other tools tried first? What happened?
- Where did genuine manual intervention or verification end up being needed?
- Rough time estimate for this exercise with AI assistance vs. an estimate for doing it without
  AI at all.
- Prior front-end/React experience, if any?
- Was this style of AI-assisted work enjoyable? Any other reflections?
