# rfq-negotiation

Quote-driven negotiation over a central limit order book: a taker asks several
market makers for a price, the makers answer, and either side may improve on the
other until someone trades or the clock runs out.

The interesting part is not the happy path. Every participant may act at any
instant, several negotiations run at once over the same book, four clocks are
running, and the same price can be taken by somebody who was never part of the
conversation. This service decides who is told what, and when.

## What it does

A negotiation has three things in it, and they end independently:

|                   | Belongs to | Ends when                                                  |
| ----------------- | ---------- | ---------------------------------------------------------- |
| **Quote request** | the taker  | filled, withdrawn, or its clock runs out                   |
| **Solicitation**  | the system | every maker has answered, or its own longer clock runs out |
| **Quote**         | a maker    | both legs are gone — traded, pulled, or expired            |

A request does not end because a maker stopped quoting, and a maker's price does
not die because the taker walked away. Liquidity outlives the negotiation that
summoned it, and the next taker can hit it.

### Quotes and counters are orders

Every price in a negotiation rests in the book as an ordinary order. There is no
second execution path, so every trade is a genuine price–time match and a maker
who was never solicited can hit a taker's counter — which is exactly the point.
The taker asked for size, not for size from a particular party.

That single decision removes most of what makes these systems fragile: there is
no separate negotiation matcher to keep consistent with the book, no private
crossing, and no way for the two to disagree about what traded.

### Every quote is two-way

A maker returns a bid _and_ an offer. Asking for one side tells the room which
way you are going before you have traded, so the request carries a size and an
instrument and no direction. The taker reveals direction when they commit, which
is the moment it stops being information anyone can trade ahead of.

### The best maker hears first

A maker who quoted the best price gets a short head start on the taker's counter
before the others see it. Priority is _awareness_, not access — the counter is
resting in the public book the whole time, so nothing is hidden and no order is
privileged. What is rationed is who is told to look.

### Improvement and degradation

Participants are told when the top of book moves for or against what they were
last shown, with the new price. A taker who accepts a price that has already
gone gets the ordinary degradation event rather than a rejection: the client
knows what it accepted and can say "traded away" over the top of it.

Movement is published from a poll rather than on every book change. On an active
instrument, comparing top of book on every mutation is a firehose, and most of
those frames tell a participant nothing they can act on. Between polls the
participant sees the net result, which is what they would have acted on anyway.

## The events

Every event names its audience, because who hears it is the design. They divide
by the thing they are about — request, solicitation, quote, counter, trade — and
each has a direction: inbound events are what a participant asked for, outbound
events are what the system decided to tell them.

Run the tests to see them: each test asserts _who_ was told _what_, so a service
that quietly told nobody would fail even with correct internal state.

## Running it

```sh
./gradlew build
```

Java 25, no external services. The book and the event transport are ports
(`OrderBook`, `EventSink`), so the whole negotiation runs in a test against a
fake book and a recording sink.

## What is not built yet

Stated plainly because a reviewer will find them faster than a roadmap will:

- **Last look.** Designed, not implemented: where the setting lives, what a
  rejection does to the held quote, and how firm and last-look prices are told
  apart by the taker.
- **Credit and funding.** The model assumes pre-funded wallets checked before a
  commitment rests. Nothing checks them yet.
- **Transport.** No process entry point: this is the domain and its ports.

## Two things it does that are easy to get wrong

**A request cannot be committed twice.** It tracks what is outstanding *and*
what is already working on the book in the taker's name. A counter resting for
the full size leaves no room to accept a maker's price as well — one requirement
cannot be committed twice, and both commitments could otherwise fill. A counter
reserves for as long as it rests; an accept is checked but reserves nothing,
because it is decided in the instant it is placed while a counter spends the
requirement for as long as it sits there.

Refused rather than reduced to fit. A taker asking to commit more than remains
meant something different from one asking for what is left, and quietly filling
the smaller size hands them a position they did not choose — so the refusal
carries the size that *would* fit.

**The same execution applied twice does nothing the second time.** Fills carry an
execution id, not just an order id. Order id cannot stand in for it: two genuine
partial fills of 400 on one order are indistinguishable from one fill of 400
delivered twice, and the transport is entitled to redeliver. Both cases have a
test, and they assert opposite outcomes — the second is the one that fails
against every shortcut version of the fix.

## Design

The full design, including the negotiation flows and the reasoning behind the
choices above, is written up separately and kept under version control alongside
it.

## Technologies

Java 25, Gradle, JUnit 6, JaCoCo, Spotless.
