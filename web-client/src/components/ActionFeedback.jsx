// Shared success/error banner rendering for every action form (Open/Participate/Order/Close) -- one small
// component instead of repeating the same two conditionals in each form.
export default function ActionFeedback({ successLines, error }) {
  if (error) {
    return <div className="error-banner">{error}</div>;
  }
  if (successLines) {
    return (
      <div className="success-banner">
        {successLines.map((line, i) => (
          <div key={i}>{line}</div>
        ))}
      </div>
    );
  }
  return null;
}
