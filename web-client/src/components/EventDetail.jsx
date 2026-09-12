import StatusBadge from "./StatusBadge.jsx";
import EventActionsPanel from "./EventActionsPanel.jsx";
import {
  dollars,
  nullableDollars,
  money,
  tradeTimestamp,
  tradingMethodLabel,
  orderSideLabel
} from "../formatters.js";

// Rendering of one EventStatusDto: every field the JavaFX EventStatusPanelBuilder shows (prices/shares, MM account
// state, trade history (LMSR), per-option order books + participants (Order Book only -- empty/null for LMSR, per
// EventStatusDto's own doc)), plus the status-appropriate action controls (EventActionsPanel) beneath it.
export default function EventDetail({ status, username, onActionSuccess }) {
  const isLmsr = status.tradingMethod === "LMSR";

  return (
    <div className="panel" style={{ flex: 1, overflowY: "auto" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
        <h2 style={{ margin: 0, fontSize: 16 }}>{status.eventName}</h2>
        <StatusBadge status={status.status} />
      </div>

      <div className="stat-grid">
        <Stat label="Market Maker" value={status.marketMakerUsername} />
        <Stat label="Trading Method" value={tradingMethodLabel(status.tradingMethod)} />
        <Stat label="MM Balance" value={dollars(status.marketMakerBalance)} />
        <Stat label="Commission Collected" value={dollars(status.totalCommissionCollected)} />
        {status.status === "CLOSED" && <Stat label="Winning Option" value={status.winningOptionName ?? "—"} />}
      </div>

      <div className="stat-grid">
        <Stat
          label={status.optionOneName}
          value={isLmsr ? `${dollars(status.optionOnePrice)} · ${money(status.optionOneShares)} sh` : `${money(status.optionOneShares)} sh`}
        />
        <Stat
          label={status.optionTwoName}
          value={isLmsr ? `${dollars(status.optionTwoPrice)} · ${money(status.optionTwoShares)} sh` : `${money(status.optionTwoShares)} sh`}
        />
      </div>

      {isLmsr ? <TradeHistory trades={status.tradeHistory} /> : <OrderBookSection status={status} />}

      <EventActionsPanel status={status} username={username} onSuccess={onActionSuccess} />
    </div>
  );
}

function Stat({ label, value }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  );
}

function TradeHistory({ trades }) {
  return (
    <>
      <div className="panel-title">Trade History</div>
      {trades.length === 0 ? (
        <div className="list-empty">No trades yet.</div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Time</th>
                <th>Option</th>
                <th>Qty</th>
                <th>Price/Share</th>
                <th>Commission</th>
                <th>Total</th>
              </tr>
            </thead>
            <tbody>
              {trades.map((trade, i) => (
                <tr key={i}>
                  <td>{tradeTimestamp(trade.timestamp)}</td>
                  <td>{trade.optionName}</td>
                  <td className="num">{money(trade.quantity)}</td>
                  <td className="num">{dollars(trade.pricePerShare)}</td>
                  <td className="num">{dollars(trade.commissionPaid)}</td>
                  <td className="num">{dollars(trade.totalPaid)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function OrderBookSection({ status }) {
  const books = status.orderBooks ?? [];
  const participants = status.participants ?? [];

  return (
    <>
      <div className="panel-title">Order Books</div>
      {books.length === 0 ? (
        <div className="list-empty">No order book data.</div>
      ) : (
        books.map((book) => <OneOrderBook key={book.optionName} book={book} />)
      )}

      <div className="panel-title" style={{ marginTop: 14 }}>
        Participants
      </div>
      {participants.length === 0 ? (
        <div className="list-empty">No participants yet.</div>
      ) : (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>User</th>
                <th>{status.optionOneName} Shares</th>
                <th>{status.optionOneName} Value</th>
                <th>{status.optionTwoName} Shares</th>
                <th>{status.optionTwoName} Value</th>
              </tr>
            </thead>
            <tbody>
              {participants.map((p) => (
                <tr key={p.username}>
                  <td>{p.username}</td>
                  <td className="num">{money(p.optionOneShares)}</td>
                  <td className="num">{dollars(p.optionOneValue)}</td>
                  <td className="num">{money(p.optionTwoShares)}</td>
                  <td className="num">{dollars(p.optionTwoValue)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function OneOrderBook({ book }) {
  return (
    <div style={{ marginBottom: 14 }}>
      <div className="order-book-option-header">{book.optionName}</div>
      <div className="stat-grid" style={{ marginBottom: 8 }}>
        <Stat label="Last" value={nullableDollars(book.lastPrice)} />
        <Stat label="Bid" value={nullableDollars(book.bidPrice)} />
        <Stat label="Ask" value={nullableDollars(book.askPrice)} />
        <Stat label="Mid" value={nullableDollars(book.midPrice)} />
        <Stat label="Spread" value={nullableDollars(book.spread)} />
      </div>
      <div className="order-book-columns">
        <OrderSideTable title="Bids" orders={book.restingBids} />
        <OrderSideTable title="Asks" orders={book.restingAsks} />
      </div>
    </div>
  );
}

function OrderSideTable({ title, orders }) {
  return (
    <div className="order-book-side">
      <div className="panel-title">{title}</div>
      {orders.length === 0 ? (
        <div className="list-empty">None resting.</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>User</th>
              <th>Side</th>
              <th>Qty</th>
              <th>Price</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order, i) => (
              <tr key={i}>
                <td>{order.username}</td>
                <td className={order.side === "BUY" ? "side-buy" : "side-sell"}>{orderSideLabel(order.side)}</td>
                <td className="num">{money(order.quantity)}</td>
                <td className="num">{dollars(order.price)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
