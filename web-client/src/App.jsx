import { useEffect, useState } from "react";
import { getUser, logout } from "./api/client.js";
import LoginScreen from "./screens/LoginScreen.jsx";
import EventsScreen from "./screens/EventsScreen.jsx";
import UserScreen from "./screens/UserScreen.jsx";

export default function App() {
  // null = not logged in, undefined = still probing for an existing session, string = logged in as that user.
  const [username, setUsername] = useState(undefined);
  const [tab, setTab] = useState("events");

  useEffect(() => {
    // The session cookie (set by POST /login, read by every session-scoped servlet -- server.SessionUtils)
    // survives a page refresh even though this component's own state does not. GET /user with no ?username=
    // defaults to the caller's own session identity server-side (UserDetailServlet) and 401s with no session --
    // exactly the signal needed to silently decide "already logged in" vs. "show the login screen" on first load.
    getUser(undefined)
      .then((detail) => setUsername(detail.username))
      .catch(() => setUsername(null));
  }, []);

  async function handleLogout() {
    try {
      await logout();
    } finally {
      setUsername(null);
      setTab("events");
    }
  }

  if (username === undefined) {
    return <div className="placeholder">Loading…</div>;
  }

  if (username === null) {
    return (
      <div className="app-shell">
        <LoginScreen onLoggedIn={setUsername} />
      </div>
    );
  }

  return (
    <div className="app-shell">
      <div className="topbar">
        <div className="brand">GUESS MARKET</div>
        <div className="topbar-right">
          <span>
            Signed in as <span className="topbar-username">{username}</span>
          </span>
          <button onClick={handleLogout}>Log Out</button>
        </div>
      </div>
      <div className="tabs">
        <button className={`tab-button ${tab === "events" ? "active" : ""}`} onClick={() => setTab("events")}>
          Events
        </button>
        <button className={`tab-button ${tab === "account" ? "active" : ""}`} onClick={() => setTab("account")}>
          My Account
        </button>
      </div>
      {tab === "events" ? <EventsScreen username={username} /> : <UserScreen username={username} />}
    </div>
  );
}
