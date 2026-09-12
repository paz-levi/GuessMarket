import OpenEventForm from "./OpenEventForm.jsx";
import ParticipateForm from "./ParticipateForm.jsx";
import OrderSubmissionForm from "./OrderSubmissionForm.jsx";
import CloseEventForm from "./CloseEventForm.jsx";

// Picks the action control(s) that make sense for one event's current status -- the web-client analogue of
// gui.components.EventActionsPanelBuilder.build, same switch-by-status shape, but with one deliberate difference
// from the JavaFX reference: Open/Close are gated in the UI to the event's own market maker
// (username === status.marketMakerUsername) rather than rendered unconditionally for every logged-in user. The
// server's own UnauthorizedMarketMakerException remains the real enforcement either way -- this is purely about
// not showing a control that would only ever fail for whoever's looking at it.
export default function EventActionsPanel({ status, username, onSuccess }) {
  const isMarketMaker = username === status.marketMakerUsername;

  if (status.status === "NOT_STARTED") {
    return isMarketMaker ? (
      <OpenEventForm eventName={status.eventName} username={username} onSuccess={onSuccess} />
    ) : (
      <div className="action-caption">
        This event has not been opened yet — its market maker ({status.marketMakerUsername}) can open it.
      </div>
    );
  }

  if (status.status === "ACTIVE") {
    const tradingForm =
      status.tradingMethod === "ORDER_BOOK" ? (
        <OrderSubmissionForm
          eventName={status.eventName}
          optionOneName={status.optionOneName}
          optionTwoName={status.optionTwoName}
          username={username}
          onSuccess={onSuccess}
        />
      ) : (
        <ParticipateForm
          eventName={status.eventName}
          optionOneName={status.optionOneName}
          optionTwoName={status.optionTwoName}
          username={username}
          onSuccess={onSuccess}
        />
      );

    return (
      <>
        {tradingForm}
        {isMarketMaker && (
          <CloseEventForm
            eventName={status.eventName}
            optionOneName={status.optionOneName}
            optionTwoName={status.optionTwoName}
            username={username}
            onSuccess={onSuccess}
          />
        )}
      </>
    );
  }

  // CLOSED -- matches EventActionsPanelBuilder.build's own CLOSED branch: no controls, just a plain sentence.
  return <div className="action-caption">This event is closed and no longer accepts trades.</div>;
}
