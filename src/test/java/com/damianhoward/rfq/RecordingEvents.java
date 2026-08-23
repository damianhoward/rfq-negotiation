package com.damianhoward.rfq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.damianhoward.rfq.event.NegotiationEvent;
import com.damianhoward.rfq.model.ParticipantId;
import com.damianhoward.rfq.port.EventSink;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects what the service published, and asks questions about it.
 *
 * <p>Every assertion here is about <em>who</em> was told <em>what</em>, because that is what the
 * negotiation is: the book decides trades, this service decides who hears about them. A test that
 * only checked state would pass on a service that silently told nobody.
 */
final class RecordingEvents implements EventSink {

  private final List<NegotiationEvent> published = new ArrayList<>();

  @Override
  public void publish(NegotiationEvent event) {
    published.add(event);
  }

  List<NegotiationEvent> all() {
    return List.copyOf(published);
  }

  /** Every event of this type, in the order it was published. */
  <T extends NegotiationEvent> List<T> ofType(Class<T> type) {
    return published.stream().filter(type::isInstance).map(type::cast).toList();
  }

  /** Every event of this type sent to this participant. */
  <T extends NegotiationEvent> List<T> to(ParticipantId who, Class<T> type) {
    return ofType(type).stream().filter(event -> event.audience().equals(who)).toList();
  }

  <T extends NegotiationEvent> T onlyTo(ParticipantId who, Class<T> type) {
    List<T> found = to(who, type);
    assertEquals(1, found.size(), () -> "expected exactly one " + type.getSimpleName()
        + " for " + who + ", got " + found.size() + " — all events: " + published);
    return found.getFirst();
  }

  <T extends NegotiationEvent> void none(Class<T> type) {
    assertTrue(ofType(type).isEmpty(),
        () -> "expected no " + type.getSimpleName() + ", got " + ofType(type));
  }

  <T extends NegotiationEvent> void none(ParticipantId who, Class<T> type) {
    assertTrue(to(who, type).isEmpty(),
        () -> "expected no " + type.getSimpleName() + " for " + who + ", got " + to(who, type));
  }

  /** Forgets everything so far, so a test can assert about one phase without the setup noise. */
  void clear() {
    published.clear();
  }
}
