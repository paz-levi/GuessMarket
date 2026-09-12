import { useState } from "react";
import { login, ApiError } from "../api/client.js";

// Matches the JavaFX client's own login requirements: name only, no password. A name already taken comes back as
// a 409 (UserAlreadyExistsException) -- shown inline, the user can just try a different name, no separate
// "returning user" flow exists since the server holds no persistence across restarts.
export default function LoginScreen({ onLoggedIn }) {
  const [username, setUsername] = useState("");
  const [error, setError] = useState(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    const trimmed = username.trim();
    if (!trimmed) {
      setError("Enter a username.");
      return;
    }
    setPending(true);
    setError(null);
    try {
      await login(trimmed);
      onLoggedIn(trimmed);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not log in.");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-title">GUESS MARKET</div>
        <div className="login-subtitle">Sign in with a username to continue</div>
        <div className="field">
          <label>Username</label>
          <input
            type="text"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            placeholder="e.g. trader1"
          />
        </div>
        {error && <div className="error-banner">{error}</div>}
        <button type="submit" className="primary" disabled={pending}>
          {pending ? "Signing in…" : "Sign In"}
        </button>
      </form>
    </div>
  );
}
