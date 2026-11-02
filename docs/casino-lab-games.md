# Casino lab — game rules and payouts

Design note for the first three games in the casino-lab phase: coin toss,
dice, and a 3-reel slot machine. Covers the rules, the payout math, why
those specific numbers were chosen, and the shared data model all three
write into. Play money only - see `docs/casino-lab-design.md` for the
overall scope and ground rules for this phase.

## Why the house wins honestly

Every game here uses a genuinely fair random number - a real 50/50 coin,
a real 1-in-6 die, a real independent draw per reel. The house edge never
comes from tilting the randomness. It comes from paying out slightly less
than a mathematically fair bet would pay, which is also how real-money
casino games are built: "return to player" (RTP) is the published share
of money a game pays back over the long run, and "house edge" is just
100% minus that. A 95% RTP game pays back $0.95 for every dollar wagered,
on average, over a large number of rounds. In any single round, the
player can obviously still win big - the edge only shows up over volume,
which is exactly the kind of thing the monitoring stack in this lab
should be able to see happening in aggregate (see "What this feeds" below).

Three games, three different edges, on purpose - it mirrors how real
platforms work: the simplest even-money bet carries the smallest edge,
a straight number-guess carries a middling edge, and the slot machine
(the most "produced" game, with the most ways to almost-win) carries the
largest. None of these numbers are official industry figures - they're
reasonable, defensible choices for a lab, landing inside the ranges real
jurisdictions actually regulate real slot and table games to.

## Coin toss

Player picks heads or tails before a fair flip. Correct guess wins.

- Win probability: 50%
- Payout on a win: **1.95x** the bet (bet 10, get 19.50 back; profit 9.50)
- Loss: bet is forfeited
- RTP: 97.5% · house edge: 2.5%

This is the tightest edge of the three, which fits - it's the simplest
possible bet, nothing to tune or weight, so there's no real justification
for taking a bigger cut than this.

## Dice

Player picks a single number, 1 through 6, before one die is rolled.
Correct guess wins. (A true fair payout for a 1-in-6 bet would be 6x; this
pays slightly less.)

- Win probability: 1/6 (≈16.7%)
- Payout on a win: **5.7x** the bet (bet 10, get 57 back; profit 47)
- Loss: bet is forfeited
- RTP: 95% · house edge: 5%

95% is a deliberately plain, recognizable RTP figure - a reasonable
middle point between the coin toss and the slot machine.

## Slot machine

Three independent reels, five symbols per reel, same weighting on every
reel. Weights control how often each symbol comes up - this is the same
mechanism real slot machines use (weighted "virtual" reel stops) to make
common symbols land often and rare ones land very rarely, without needing
unequal physical symbols.

Per-reel symbol weights (out of 100):

| Symbol | Weight | Chance per reel |
|---|---|---|
| Cherry | 30 | 30% |
| Lemon  | 25 | 25% |
| Bell   | 20 | 20% |
| Bar    | 15 | 15% |
| Seven  | 10 | 10% |

Paytable (bet multiplier):

| Result | Payout | Chance per spin |
|---|---|---|
| Three sevens | 40x | 1 in 1,000 |
| Three bars | 30x | 1 in ~296 |
| Three bells | 15x | 1 in 125 |
| Three lemons | 8x | 1 in 64 |
| Three cherries | 5x | 1 in 37 |
| Exactly two cherries (any position) | 1.5x | 1 in ~5.3 |
| Exactly two lemons (any position) | 0.75x | 1 in ~7.1 |
| Anything else | 0 (loss) | ≈61.5% of spins |

RTP: **91.0%** · house edge: **≈9%**

That 91% isn't a guess - it's the exact result of multiplying every one
of the 125 possible reel combinations by its real probability and its
real payout (checked with a short script, not by hand). It's deliberately
the worst odds of the three games, which also matches reality: published
legal minimum RTPs for real slot machines run as low as 70-90% depending
on jurisdiction, well below the minimums set for simpler table-style
bets, so a slot machine being the "worst odds, flashiest game" in the lab
is realistic, not exaggerated.

## The shared data model

All three games write into one common record, plus their own small
detail:

**`game_round`** (shared by every game)
- player / account id
- game (`coin_toss`, `dice`, `slots`)
- bet amount
- result (`win`, `loss`)
- payout amount (0 on a loss)
- timestamp

**Game-specific detail**, attached to the round rather than replacing it:
- coin toss: which side was called, which side landed
- dice: which number was called, which number landed
- slots: the three symbols that landed

The shared `game_round` table is what everything else in this phase
depends on - it's not just a leaderboard-style stat. It's what a wallet
balance gets checked against (every credit/debit should trace back to a
round or a deposit/withdrawal), what a "house edge actually matching
design" report would be built from, and what any fraud-style monitoring
would query (e.g. one account winning far more often than its game's
real odds would predict). The per-game detail stays separate because
slots, dice, and coin toss don't share a shape, but they all produce the
same four or five facts that matter for money: who, how much, win or
lose, how much came back.

## Possible future games (not built yet)

Two candidates came up as other simple, single-RNG-event games worth
considering for a later stage, both at or below the complexity of coin
toss and dice:

- **Over/Under 7** - two dice are rolled and summed; the player bets the
  total will be over 7, under 7, or exactly 7. Same mechanics as dice,
  but the "exactly 7" bet is a nice teaching example of a worse-odds
  option sitting next to two fairer ones, since sums cluster around 7.
- **Colour wheel** - a single spin of a wheel with weighted colour
  segments (e.g. mostly red/black, a couple of rare colours). This is a
  simpler stand-in for the slot machine's weighting idea - one weighted
  draw instead of three - and could be built before slots as a stepping
  stone.

Neither is scoped or built. Listed here so the option isn't lost; the
build order and whether to include them is still open.
