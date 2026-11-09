# Session 8 — Stage 1: accounts (signup, verification, login) proven

## What this session was about

Stage 0 proved the Flyway/database-migration change didn't break anything.
Stage 1 is the first actual new feature for the casino-lab: letting a real
customer sign up, confirm their email with a one-time code, and log in,
with their login remembered across requests. No wallet and no games yet -
just the identity piece everything else will attach to later.

## What got built

- A new `accounts` table, kept completely separate from the existing
  `players` leaderboard table.
- Signup: takes an email and password, stores the password scrambled
  (hashed, never as plain text), and generates a random 6-digit code.
- That code gets emailed - except there's no real email server involved.
  A fake one (MailHog) was added to the test setup. It "receives" the
  email and holds it, so it can be read back and never actually leaves
  the machine.
- Verification: checking that the 6-digit code someone typed in actually
  matches the one that was generated, and that it hasn't expired (codes
  are only valid for 10 minutes).
- Login: checking the password, then remembering that you're logged in
  using a session that's stored in Redis - meaning it would still work
  correctly even if there were multiple copies of the app running behind
  a load balancer later, not just on one machine's local memory.

## The proof: what was actually tested, for real

Six throwaway containers were spun up (MySQL, Redis, RabbitMQ, the fake
mail server, and the app itself), then exercised through the real
commands a real customer's web browser would eventually trigger:

- Signed up a test account - got the "check your email" response, and a
  real email actually showed up in the fake mail server with a real
  6-digit code in it.
- Typed that code in - account flipped to verified.
- Logged in with the real password - got a session cookie back.
- Used that *exact same* cookie on a brand new, separate request to
  `/api/accounts/me` - and it still said "logged in." This was the one
  that actually mattered: it proves the login is being remembered by
  Redis between requests, not just held in the one request that logged
  in.
- Checked the security details too: sending no cookie at all correctly
  said "not logged in." Trying a wrong password, and trying an email
  that was never signed up at all, both came back with the exact same
  generic "invalid email or password" message - on purpose, so nobody
  can use the error message to figure out which emails are real
  customers.

All of it passed. Containers were torn down afterward (`docker compose
... down -v`) - that stack was always meant to be thrown away once it had
done its job.

## Real mistakes hit along the way (and the actual fixes)

**PowerShell + curl + quotes, round two.** Typing a JSON body directly on
the command line with escaped quotes (`-d "{\"email\":...}"`) looked fine
but silently got mangled by PowerShell before curl ever saw it - twice,
in two different ways, including once where curl itself got confused and
threw an unrelated-looking error about "a backslash where a forward slash
was expected." The actual fix: write the JSON into a small file first
(`'{"email":...}' | Set-Content -Path x.json -NoNewline -Encoding ascii`)
and have curl read the file instead (`-d "@x.json"`) - nothing left on
the command line for PowerShell to misinterpret. `STAGE1-PROOF.md` has
been rewritten to use this everywhere, instead of the version that looked
fine on paper but didn't actually work.

**The 10-minute code expired mid-session - for a real reason.** Codes are
only valid for 10 minutes on purpose (a code that worked forever would be
a security weakness). A long side conversation about the overall project
roadmap happened between signing up and typing the code in, and by the
time it was tried, more than 10 minutes had passed - so it correctly
said "no pending code... or it expired." Not a bug; the system did
exactly what it's supposed to do. The real lesson: when testing anything
with a timer attached, run the steps back-to-back instead of leaving a
long gap in the middle.

## A real gap this surfaced (left alone for now, on purpose)

Right now, if someone's code expires - or they lose the email, or
anything else goes wrong before they verify - there's no way to get a
new one. Signing up again with the same email just gets refused as
"already registered," with no path to a fresh code. In a real product
this would permanently strand that person. Since this is still a one-
person test with no other customers touching it yet, this was
deliberately left unfixed for now rather than rushed - worth fixing
before this ever has more than one user, not before.

## A planning conversation worth recording

Mid-session, it became clear the actual end goal is a real website - sign
up and log in by filling out a form in a browser and clicking, not by
typing commands - plus a separate back-office portal for staff to see
customer accounts and activity, which was already quietly assumed in
`casino-lab-design.md`'s "not part of the public-facing site" line but
had never actually been written down as its own item. `casino-lab-
design.md` has been updated to say this explicitly. Decided order: finish
proving each backend piece the way Stage 0 and Stage 1 were just proven,
*then* build the actual website on top of it, rather than building
screens for an API that isn't trustworthy yet.

## Where this leaves things

Proven, not yet committed - same as always, nothing goes into git until
that's explicitly asked for. Everything built tonight exists as real code
in the branch `casino-lab-stage1-accounts`, ready to commit once given
the go-ahead.
