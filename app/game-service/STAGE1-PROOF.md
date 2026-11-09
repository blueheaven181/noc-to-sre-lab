# Stage 1 proof: accounts (signup, email verification, login)

What this proves: the whole new accounts flow actually works end-to-end
against real containers - not just "the code compiles." Signup creates a
real row in a real MySQL table (via the new V2 migration), the
verification code really does get sent somewhere and really does have to
match, and a successful login really does get remembered by a real
Redis-backed session cookie across separate requests.

Run everything below from `app/game-service/` on a machine with Docker
installed. Use `curl.exe` (not plain `curl`) in PowerShell - PowerShell's
own `curl` is actually a different command in disguise and doesn't take
real curl's flags the way these steps expect.

**On typing JSON directly on the command line:** don't. PowerShell
mangles escaped quotes (`-d "{\"email\":...}"`) before curl ever sees
them - sometimes silently, sometimes with a confusing unrelated-looking
error. Every step below instead writes the JSON into a small file first,
then has curl read that file (`-d "@file.json"`) - nothing is left on the
command line for PowerShell to misinterpret, and this is the version that
was actually confirmed to work.

**On timing:** verification codes expire after 10 minutes, on purpose.
Run steps 2 through 5 close together in one sitting rather than leaving a
long gap in the middle - if a code expires, that's not a bug, just redo
the signup (with a fresh email - see the known gap below) and continue
promptly.

## 1. Start the stack

```
docker compose -f docker-compose.test.yml up --build -d
```

What this does: builds and starts six throwaway containers together -
MySQL, Redis, RabbitMQ, the new fake mail server (MailHog), and
game-service itself - all wired together the same way they'll be wired on
the real lab servers later, but disposable so it's safe to break.

Watch it come up:

```
docker compose -f docker-compose.test.yml logs -f game-service
```

What to look for, in order: a Flyway line mentioning `V2__create_accounts`
(that's the new `accounts` table actually being created), no errors from
Hibernate right after it, then the normal `Started GameServiceApplication`
line. Ctrl+C out of the log stream once you see that - the containers keep
running in the background.

## 2. Sign up a test account

```
'{"email":"stage1-test@example.com","password":"TestPass123!"}' | Set-Content -Path signup.json -NoNewline -Encoding ascii
curl.exe -X POST http://localhost:8080/api/accounts/signup -H "Content-Type: application/json" -d "@signup.json"
```

What this does: calls the real signup endpoint with a made-up email and
password. Behind the scenes this creates the account row in MySQL
(password stored hashed, never in plain text), generates a random 6-digit
code, saves that code in Redis for 10 minutes, and sends an email
containing the code to the fake mail server.

Expect back: `{"status":"signup accepted, check your email for a
verification code"}`.

### Worth checking: signing up the same email twice

```
curl.exe -X POST http://localhost:8080/api/accounts/signup -H "Content-Type: application/json" -d "@signup.json"
```

What this proves: the unique-email rule actually holds. Expect a 409
response this time: `{"status":"email already registered"}`.

## 3. Find the verification code

```
curl.exe http://localhost:8025/api/v2/messages
```

What this does: asks the fake mail server for every email it's "received"
(nothing left this machine - MailHog just holds them). In the response,
find the email addressed to `stage1-test@example.com` and look inside its
text body for a line like `Your noc-to-sre-lab verification code is:
042917`. That 6-digit number is the real, just-generated code - write it
down.

(There's also a human-friendly web page for this at
`http://localhost:8025` in a browser, if digging through JSON output isn't
appealing.)

## 4. Verify the code

```
'{"email":"stage1-test@example.com","code":"<the 6 digits from step 3>"}' | Set-Content -Path verify.json -NoNewline -Encoding ascii
curl.exe -X POST http://localhost:8080/api/accounts/verify -H "Content-Type: application/json" -d "@verify.json"
```

Expect back: `{"status":"verified"}`.

### Worth checking: a wrong code

Sign up a second throwaway address first (so there's a real pending code
to test against), then try a code that doesn't match it:

```
'{"email":"stage1-test@example.com","code":"000000"}' | Set-Content -Path verify-wrong.json -NoNewline -Encoding ascii
curl.exe -X POST http://localhost:8080/api/accounts/verify -H "Content-Type: application/json" -d "@verify-wrong.json"
```

What this proves: a guessed/wrong code is actually rejected. Expect a 400
response: `{"status":"code did not match"}`. (If the real code has
already expired by the time this is tried, you'll instead see "no
pending code for this email" - that's a different, also-correct outcome,
just not quite this specific check.)

## 5. Log in and prove the session actually persists

```
'{"email":"stage1-test@example.com","password":"TestPass123!"}' | Set-Content -Path login.json -NoNewline -Encoding ascii
curl.exe -c cookies.txt -X POST http://localhost:8080/api/accounts/login -H "Content-Type: application/json" -d "@login.json"
```

What this does: logs in with the real password, and `-c cookies.txt` tells
curl to save whatever session cookie the server hands back into a local
file, the same way a browser would.

Expect back: `{"status":"logged in","email":"stage1-test@example.com"}`.

Now, in a **separate request** (the whole point of this step), send that
saved cookie back and ask who's logged in:

```
curl.exe -b cookies.txt http://localhost:8080/api/accounts/me
```

Expect back: `{"status":"logged in","email":"stage1-test@example.com"}`
again. This is the actual proof that matters: if this works, the session
really is being remembered by Redis between two completely separate curl
calls - not just held in memory for the one request that logged in.

As a sanity check, try the same `/me` call with no cookie at all:

```
curl.exe http://localhost:8080/api/accounts/me
```

Expect a 401 response: `{"status":"not logged in"}`.

### Worth checking: a wrong password gives the same error as no such account

```
'{"email":"stage1-test@example.com","password":"wrong-password"}' | Set-Content -Path wrongpass.json -NoNewline -Encoding ascii
curl.exe -X POST http://localhost:8080/api/accounts/login -H "Content-Type: application/json" -d "@wrongpass.json"
'{"email":"nobody-signed-up-with-this@example.com","password":"anything"}' | Set-Content -Path noaccount.json -NoNewline -Encoding ascii
curl.exe -X POST http://localhost:8080/api/accounts/login -H "Content-Type: application/json" -d "@noaccount.json"
```

What this proves: both should come back with the exact same 401 message -
`{"status":"invalid email or password"}` - never anything that would let
someone fish for which email addresses are actually registered.

## Known gap (not tested here, not yet fixed)

If a code expires, or the "email" is lost, there is currently no way to
get a new one - signing up again with the same, already-registered email
is refused outright with no fresh code issued. That means a real customer
in that situation would be stuck permanently. Fine for solo testing right
now; worth fixing before anyone other than one person is ever using this.

## 6. Record the result

Paste the output of steps 1-5 back so it can go in the journal entry -
pass or fail, either way that's the actual record, not a guess.

## 7. Clean up

```
docker compose -f docker-compose.test.yml down -v
```

The `-v` drops the test database/volumes/mail too - this stack is
throwaway, not something to keep running.
