package com.damianhoward.rfq.port;

import com.damianhoward.rfq.model.Instrument;
import com.damianhoward.rfq.model.OrderId;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.model.Price;
import com.damianhoward.rfq.model.Quantity;
import com.damianhoward.rfq.model.Side;
import com.damianhoward.rfq.model.TimeInForce;
import com.damianhoward.rfq.model.TopOfBook;
import java.time.Instant;

/**
 * The book, as this service needs it.
 *
 * <p>Deliberately narrow. The negotiation solicits, observes and notifies; it does not match, hold
 * quotes or decide prices, so it needs exactly three things from a book: put an order in, take one
 * out, and read the top. Everything else — priority, matching, self-match prevention — is the
 * book's and stays there.
 *
 * <p>Defining the port here rather than depending on a book implementation is what keeps this
 * module pure domain: the tests drive a fake, and the real adapter is written once, at the edge.
 */
public interface OrderBook {

  /**
   * Rests an order and returns the book's identifier for it.
   *
   * @param owner whose order it is — never absent, and what self-match prevention keys on
   * @param timeInForce whether it rests, discards its remainder, or is all-or-nothing
   * @param goodTill when it stops being tradeable
   */
  OrderId place(
      Instrument instrument,
      ParticipantId owner,
      Side side,
      Price price,
      Quantity size,
      TimeInForce timeInForce,
      Instant goodTill);

  /**
   * Takes an order back off, returning false where it was not there — already filled, already
   * cancelled, or never placed. Those are the same answer to this service, and none is exceptional.
   */
  boolean cancel(OrderId orderId);

  /** The best bid and offer right now, either side possibly absent. */
  TopOfBook topOfBook(Instrument instrument);
}
