# Sizing Modbus queries on an Inferrix controller

How to work out, before you hit Apply, whether the queries on a controller's RS-485 bus will fit —
and what to change when they don't.

If a config is rejected with **`ICC_BUS_OVERSUBSCRIBED`** ("The queries on one bus ask for more
traffic than its baud rate allows"), this page is the arithmetic the controller just did.

---

## The rule

The controller checks each bus independently. For every query on that bus it adds up the
**worst-case** time the query can occupy the wire — assuming the slave never answers and every retry
fires — and compares the total against the **tightest poll interval on that bus**.

```
frame_ms = ceil(bytes x 11 x 1000 / baud) + 4
    where bytes = 8 + response_bytes
          response_bytes = 5 + 2 x count          for FC3 / FC4  (registers)
                         = 5 + ceil(count / 8)    for FC1 / FC2  (bits)

busy   = SUM over every query on the bus of  (frame_ms + timeout_ms) x (1 + retries)
budget = MIN over every query on the bus of  interval_ms

reject if  busy > budget
```

`8` is the request frame, `5` is the response header plus CRC, `11` is bits per byte on the wire
(start + 8 data + parity/stop), `+4 ms` is turnaround margin.

Source of truth: `lib/icc/icc_verify.c`, `query_frame_ms()` and the bus loop in
`check_referential_integrity()`.

---

## The part that catches people

> **`budget` is the *minimum* interval on the bus, not each query's own interval.**

One fast query sets the budget for every query sharing that bus. So if a bus already polls something
at 1000 ms, adding a slow sensor at 10000 ms does **not** buy you room — the budget stays 1000 ms
and your new query's cost still lands in `busy`.

This is the usual reason a change that "should obviously fit" is rejected. Raising the new query's
interval is the instinct, and it is the one change that cannot help.

The second surprise: `timeout_ms` dominates, not the data. At 9600 baud a two-register read spends
**24 ms** on the wire but a 200 ms timeout with one retry books **448 ms**. Query cost is almost
entirely the timeout you chose, so timeouts are where the room is.

---

## Procedure

1. **List every query already on the bus**, not just the one you are adding. Controller →
   Configuration → Queries, filtered by Bus.
2. **Look up `frame_ms`** for each from the tables below, using that bus's baud rate.
3. **Cost each query**: `(frame_ms + timeout_ms) x (1 + retries)`.
4. **Add them up** — that is `busy`.
5. **Take the smallest `interval_ms`** on the bus — that is `budget`.
6. `busy <= budget` passes. Aim for 70–80% so the next sensor still fits.

---

## Frame time in milliseconds

**Registers — FC3 (Holding) and FC4 (Input)**

| count | 9600 | 19200 | 38400 | 57600 | 115200 |
|---|---|---|---|---|---|
| 1 | 22 | 13 | 9 | 7 | 6 |
| 2 | 24 | 14 | 9 | 8 | 6 |
| 4 | 29 | 17 | 11 | 9 | 7 |
| 8 | 38 | 21 | 13 | 10 | 7 |
| 16 | 56 | 30 | 17 | 13 | 9 |
| 32 | 93 | 49 | 27 | 19 | 12 |
| 64 | 166 | 85 | 45 | 31 | 18 |
| 125 | 306 | 155 | 80 | 55 | 30 |

**Bits — FC1 (Coils) and FC2 (Discrete Inputs)**

| count | 9600 | 19200 | 38400 | 57600 | 115200 |
|---|---|---|---|---|---|
| 1 | 21 | 13 | 9 | 7 | 6 |
| 8 | 21 | 13 | 9 | 7 | 6 |
| 16 | 22 | 13 | 9 | 7 | 6 |
| 32 | 24 | 14 | 9 | 8 | 6 |
| 64 | 29 | 17 | 11 | 9 | 7 |
| 128 | 38 | 21 | 13 | 10 | 7 |
| 256 | 56 | 30 | 17 | 13 | 9 |
| 2000 | 306 | 155 | 80 | 55 | 30 |

125 registers and 2000 bits are the Modbus per-request maximums.

---

## Worked example

Bus 0 at 9600 baud, four queries: the board's own DI and DO blocks, a Selec energy meter and an
XY-MD02 temperature/humidity sensor.

| Query | FC | count | frame_ms | timeout | retries | cost |
|---|---|---|---|---|---|---|
| 1 — local DI | 2 | 4 | 21 | 100 | 1 | (21+100) x 2 = **242** |
| 2 — local DO | 1 | 4 | 21 | 100 | 1 | (21+100) x 2 = **242** |
| 3 — Selec frequency | 4 | 2 | 24 | 200 | 1 | (24+200) x 2 = **448** |
| 4 — XY-MD02 temp+hum | 4 | 2 | 24 | 200 | 1 | (24+200) x 2 = **448** |
| | | | | | | **busy = 1380** |

With every interval at 2000 ms, `budget = 2000`. 1380 <= 2000 — **passes at 69%**.

Leave one interval at 1000 ms and `budget` drops to 1000 while `busy` stays 1380 — rejected, even
though three of the four queries are asking for 2 s.

---

## When it is rejected

In rough order of what costs you least:

**Cut the timeouts.** The usual default of 200 ms is generous for a frame that takes 24 ms. Most
slaves answer within 50 ms; 100 ms is a realistic ceiling for a healthy RS-485 device. Halving a
timeout halves that query's cost.

**Drop retries on tolerant points.** `retries: 0` halves the cost outright. Reasonable for a
slow-moving measurement that is published on change — a single missed frame just delays the next
reading. Keep retries on anything driving control logic.

**Raise the intervals — all of them.** Because `budget` is the minimum, every query on the bus has
to move. Raising one alone does nothing.

**Merge adjacent registers into one query.** Contiguous addresses cost far less read together: two
separate 1-register reads are 22 + 22 = 44 ms of frame time, one 2-register read is 24 ms — and,
more importantly, one timeout instead of two. Read the block, then separate the values with each
point's `Offset`.

**Raise the baud rate.** Only helps a little — at 9600 a small frame is already ~24 ms against a
200 ms timeout, so going to 19200 saves 10 ms out of 448. Worth it only for large register blocks.

**Not available: a second bus.** The config format allows two (`ICC_MAX_BUSES` is 2) and the
verifier will accept a bus 1, so splitting the fast devices onto their own bus looks like the clean
answer — and it is, on a board that has two ports. This one does not. `rtu_iface_get()` in
`src/comms/rtu_poller.c` answers `-ENODEV` for any `bus_id` other than 0, with the comment *"only
USART3/modbus0 exists on this board"*. A query on bus 1 passes verification, applies, and then times
out forever. So every query shares one `busy`/`budget` pair, and the five remedies above are the
whole list.

---

## Related settings that are not part of the budget

- **`Offset` on a point** is the register or bit position *inside the query window*, counted from
  the query's `First register` — not the absolute Modbus address.
- **`Source reference` on a Modbus point** is the `Query` id it reads from.
- **`First register`** is the raw, 0-based address that goes on the wire. It is not 4xxxx/3xxxx
  notation.
- **Scaling** is applied after the read as `eng = raw x multiplier / divisor + offset` in float, so a
  sensor reporting tenths needs multiplier 1, divisor 10. `65535` means no scaling. A scaled point
  always publishes as a float.
