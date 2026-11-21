# bushelworks

[![CI](https://github.com/rkaushik-dev/bushelworks/actions/workflows/ci.yml/badge.svg)](https://github.com/rkaushik-dev/bushelworks/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Gradle](https://img.shields.io/badge/build-Gradle-02303A)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-25A162)

Scale house capture and settlement for country grain elevators.

The scale house is where a load of grain stops being a truck and starts being a
number. This project holds the arithmetic that turns one into the other: gross
and tare weights, dockage, moisture shrink, grade factors, discount schedules,
contract application, storage accrual and the settlement statement that comes
out the far end.

Everything in the settlement path is exact decimal. There are no floating point
numbers in it anywhere, and quantities cross the HTTP boundary as strings so
they cannot acquire any.

## What is in here

| package | what it holds |
| --- | --- |
| `io.github.rkaushikdev.bushelworks` | the version, the settings, the errors, exact decimals and units, the house log |
| `io.github.rkaushikdev.bushelworks.domain` | the rules. No database, no clock, no network. |
| `io.github.rkaushikdev.bushelworks.schedules` | effective dated lookup for the filed tables |
| `io.github.rkaushikdev.bushelworks.services` | the order the rules run in |
| `io.github.rkaushikdev.bushelworks.persistence` | entities, mappers, repositories, the unit of work and the migrations |
| `io.github.rkaushikdev.bushelworks.api` | the HTTP interface the driveway terminal calls |
| `io.github.rkaushikdev.bushelworks.cli` | what the grain accountant runs at six in the morning |
| `io.github.rkaushikdev.bushelworks.reports` | statements, the position board, exports |
| `io.github.rkaushikdev.bushelworks.devices` | the scale indicator and the moisture meter |
| `io.github.rkaushikdev.bushelworks.release` | the release history the `changes` command prints |
| `io.github.rkaushikdev.bushelworks.reference` | the reference tables, generated from the code |

The dependency runs one way. `domain` imports nothing from the rest, and
`persistence` maps rows onto domain objects rather than the other way round.

## The two rules worth knowing before reading any of it

**A rate is resolved as of the ticket date, never as of today.** A ticket
written in October settles on October's tables however many times it is
reprinted. Nothing in `io.github.rkaushikdev.bushelworks.domain` reads the
clock.

**A correction is a second document.** Nothing is deleted and nothing is edited
in place. A ticket is voided and reissued, a settlement is reversed by writing
its opposite, and both halves stay on file. The state inspector reads the pair.

## Tech stack

- Java 21
- Spring Boot 3.5.16 with Spring Web MVC for the HTTP service, and springdoc for
  the OpenAPI document
- JPA with Hibernate for persistence, Flyway for the schema, H2 as the database
- picocli for the command line
- JUnit 5 and AssertJ for the tests, JaCoCo for coverage, Spotless with the
  Palantir format for layout
- Gradle 8.14.8, through the wrapper

## Building and running

    ./gradlew build

That runs every check there is: Spotless, a compile with `-Xlint:all -Werror`,
the whole test suite and a JaCoCo gate of 80% of lines. Run
`./gradlew spotlessApply` to lay the code out, and
`./gradlew test --tests '*.ReferenceTest'` for one test class.

    ./gradlew bootJar
    java -jar build/libs/bushelworks-0.8.0.jar --help
    java -jar build/libs/bushelworks-0.8.0.jar init-db
    java -jar build/libs/bushelworks-0.8.0.jar seed
    java -jar build/libs/bushelworks-0.8.0.jar serve

`init-db` builds the schema, `seed` fills an empty database with a week of a
working elevator, and `serve` runs the HTTP service on `127.0.0.1:8000` until it
is stopped (`--host` and `--port` move it; `--port 0` picks a free port). With
nothing configured the database is an H2 file, `bushelworks.mv.db`, in the
directory the command runs in.

## Configuration

Everything is read from the environment once, when a command or the service
starts. Every setting has a default that works on a laptop with nothing set.

| variable | default | what it sets |
| --- | --- | --- |
| `BUSHELWORKS_DATABASE_URL` | `jdbc:h2:file:./bushelworks` | the JDBC URL of the database |
| `BUSHELWORKS_ECHO_SQL` | `false` | whether the SQL the persistence layer runs is logged: `1`, `true`, `yes`, `on` or `0`, `false`, `no`, `off` |
| `BUSHELWORKS_HOUSE_NAME` | `BUSHELWORKS ELEVATOR` | the elevator's name, as statements print it |
| `BUSHELWORKS_STATE_CODE` | `KS` | the two letter state the house files with |
| `BUSHELWORKS_DEFAULT_LOCATION` | `HOME` | the location a ticket is written at when none is named |
| `BUSHELWORKS_STATEMENT_PREFIX` | `S` | the series letter settlement statements are numbered under |
| `BUSHELWORKS_TICKET_PREFIX` | `A` | the series letter scale tickets are numbered under |
| `BUSHELWORKS_MAX_PAGE_SIZE` | `200` | the most rows one page of a listing carries |
| `BUSHELWORKS_LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARNING` or `ERROR` |
| `BUSHELWORKS_RATE_LIMIT_PER_MINUTE` | `0` | calls a minute per caller; `0` turns the limit off |
| `BUSHELWORKS_API_KEYS` | empty | the keys the API accepts, comma separated, each `id:role:secret`; a bare secret is an `office` key |
| `BUSHELWORKS_MINIMUM_SETTLEMENT` | `0.00` | the smallest net a settlement run writes a cheque for |

A setting that cannot be read stops the command before it does anything. The
command line's `--database` option replaces the database URL for one run.

## The command line

    java -jar build/libs/bushelworks-0.8.0.jar tickets
    java -jar build/libs/bushelworks-0.8.0.jar settle MERRICK --location HOME --price 4.3500 --on 2024-10-20
    java -jar build/libs/bushelworks-0.8.0.jar position 2024-10-20 --format hedge

Nothing asks a question: the office runs these out of a batch file at six in
the morning. The answer goes to standard output. The house log goes to standard
error at the configured level, and so does a refusal, as the error's code and
its sentence on one line. The exit code is `0` when the command worked, `1`
when the house refused, and `2` when the arguments could not be read.
`<command> --help` describes any one of them.

| command            | what it does |
| ------------------ | --- |
| accrue             | Charge storage on every open lot up to a date. |
| allocate           | Work out what each member would get out of a patronage pool. |
| balance-sheet      | What the house owns and owes as at a day. |
| basis              | Cash less futures, for the board out front. |
| carry              | Say whether the spread pays to hold the grain rather than sell it. |
| changes            | What changed, so the house can read it before it upgrades. |
| chart              | The filed chart of accounts. |
| cheques            | Pay everything unpaid, one cheque per producer. |
| close-month        | Close a month. |
| close-year         | Empty the revenue and expense accounts into equity. |
| export-lines       | Write every printed settlement line as CSV. |
| export-settlements | Write settlement headers as CSV, one row each. |
| export-tickets     | Write scale tickets as CSV. |
| file-year          | Put twelve open months on file. |
| grade              | Regrade a stored ticket against the tables in force on its scale date. |
| import-legacy      | Read a night's file from the old system and write what it holds. |
| income             | What the house earned between two days. |
| init-db            | Build the schema, for a scratch database. |
| journal            | Every entry posted between two days. |
| lots               | Open storage lots. |
| on-hand            | Bushels the house is holding for producers. |
| open-month         | Which month a ticket keyed late belongs in. |
| patronage          | Print the patronage table for a crop year. |
| patronage-export   | Write the patronage table as CSV for the accountant. |
| position           | The daily position, as a board, a hedge sheet or a spreadsheet. |
| register           | Print the settlement register for a day. |
| reopen-month       | Open a closed month again. The return will have to be amended. |
| review             | Say whether a month would close, and write nothing. |
| seed               | Fill an empty database with a week of a working elevator. |
| serve              | Run the HTTP service until it is stopped. |
| settle             | Settle everything a producer has waiting at one elevator. |
| statement          | Reprint one statement. |
| tickets            | List the tickets waiting to be settled. |
| top                | Name the biggest producers of a crop year, biggest first. |
| trial-balance      | The books as at a day. |
| unpaid             | Everything waiting on a cheque, oldest first. |
| void               | Void a ticket, keeping its weights on file. |
| year-end           | Say what closing a year would post, and write nothing. |

The command list, the commodity master, the grade ladders and the discount
schedules are generated from the code, because the ones written out by hand
went stale twice. The suite fails when the table above or the worked load below
no longer matches what the code says. To print them:

    java -cp build/libs/bushelworks-0.8.0.jar \
        -Dloader.main=io.github.rkaushikdev.bushelworks.reference.Reference \
        org.springframework.boot.loader.launch.PropertiesLauncher --section grades --commodity SOYB

`--section` is one of `all` (the default), `commands`, `commodities`, `grades`,
`discounts` or `load`, and `--commodity` picks the ladder and the schedules,
`CORN` by default.

## A worked load

    gross          78420 lb
    tare           26180 lb
    net            52240 lb
    bushels        932.8571 at 56 lb/bu
    moisture       18.2% against a 15.0% base
    shrink         3.7760%
    dry bushels    897.6324

That load settles on 897.6324 bushels. Shrink comes off before anything is
priced, discounts come off the value, the checkoff sits on the value after
discounts, and an advance is recovered last. The order is enforced by the
statement builder rather than by the caller remembering it.

## The HTTP API

`serve` starts it. Requests and answers are JSON with snake case field names.
Every quantity and amount of money goes out as a string. Coming in, a quantity
is a string or a whole JSON number; a JSON number with a fraction is refused,
because its exact value is lost before it arrives. A refusal comes back in one
shape, with a stable `code` and a `detail` sentence: 422 when the request was
understood and refused, 404 for something not on file, 409 for a conflict, 401
for a missing or unknown key, 403 for a key not senior enough, and 429 past the
rate limit. The API document is served at `/openapi.json`, and rendered at
`/docs` and `/redoc`.

When `BUSHELWORKS_API_KEYS` is empty the house asks for no key and every caller
may do anything. Otherwise each call sends its key in the `X-API-Key` header,
and the key's role decides what it may call: `read` can see the positions,
`scale` can also write tickets and work the bin yard, and `office` can do
everything, settling, paying and amending included. The service routes at the
end answer without a key, and none of them but the sign in redirect counts
toward the rate limit.

### Tickets (`scale`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/tickets` | List tickets a page at a time: those waiting to be settled, or one producer's |
| POST | `/tickets` | Record a load across the scale |
| POST | `/tickets/batch` | Take a run of tickets the terminal held while the line was down |
| GET | `/tickets/{location_code}/{ticket_number}` | Read one ticket |
| GET | `/tickets/{location_code}/{ticket_number}/grade` | Work a stored ticket's grade out afresh |
| POST | `/tickets/{location_code}/{ticket_number}/void` | Void a ticket, keeping it on file |

### Bins (`scale`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/bins/{location_code}` | The bins at one site |
| POST | `/bins/{location_code}/loads` | Put a load away in a bin |
| GET | `/bins/{location_code}/report/export` | The bin sheet as CSV |
| GET | `/bins/{location_code}/report/sheet` | The printed bin sheet |
| GET | `/bins/{location_code}/report/summary` | What is in the yard, what room is left and what needs looking at |
| GET | `/bins/{location_code}/{bin_id}` | Read one bin |
| POST | `/bins/{location_code}/{bin_id}/draws` | Draw grain out of a bin |
| POST | `/bins/{location_code}/{bin_id}/out-of-service` | Take a bin out of service |
| POST | `/bins/{location_code}/{bin_id}/readings` | Log a cable reading, and say whether it found the bin working |

### Parties (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/parties` | List the active parties |
| POST | `/parties` | Put a party on file |
| GET | `/parties/farms/{farm_code}/agreement` | The crop share split in force on a day |
| POST | `/parties/farms/{farm_code}/agreements` | File a crop share agreement for a farm |
| GET | `/parties/{code}` | Read one party |
| POST | `/parties/{code}/checkoff-exemption` | Exempt a party from checkoff, or take the exemption away |
| POST | `/parties/{code}/closure` | Close a party's account |
| GET | `/parties/{code}/farms` | The farms a party hauls from |

### Contracts (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/contracts` | Open contracts for a producer, defaulting to those open today |
| POST | `/contracts` | Write a purchase contract |
| GET | `/contracts/{number}` | Read one contract |
| POST | `/contracts/{number}/basis` | Set the basis on a contract |
| POST | `/contracts/{number}/cancel` | Cancel a contract |
| POST | `/contracts/{number}/futures` | Set the futures price on a contract |
| POST | `/contracts/{number}/roll` | Roll a contract to another futures month |

### Settlements (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/settlements` | List settlements |
| POST | `/settlements` | Settle everything a producer has waiting at one elevator |
| GET | `/settlements/register/{on}` | The daily settlement register |
| GET | `/settlements/{number}` | Read one settlement |
| POST | `/settlements/{number}/payment` | Pay a settlement |
| GET | `/settlements/{number}/statement` | The printable eighty column statement |

### Storage (`office`)

| method | path | what it does |
| --- | --- | --- |
| POST | `/storage/accruals` | Charge storage on the open lots up to a day, or work it out as a dry run |
| GET | `/storage/lots` | List storage lots |
| POST | `/storage/lots` | Open a storage lot |
| GET | `/storage/lots/{lot_id}` | Read one lot |
| GET | `/storage/on-hand/{commodity_code}` | Bushels the house is holding for producers, which it does not own |
| POST | `/storage/receipts` | Write a warehouse receipt |
| POST | `/storage/receipts/{receipt_number}/cancel` | Cancel a warehouse receipt |
| POST | `/storage/withdrawals` | Draw a producer's bushels out, oldest lot first |

### Deferred price (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/deferred/exposure/{commodity_code}` | Bushels the house owns because nobody has priced them yet |
| GET | `/deferred/lots` | List deferred price lots |
| POST | `/deferred/lots` | Take delivery on deferred price |
| POST | `/deferred/lots/{lot_id}/pricing` | Fix a price on some or all of a lot, and say what the fee comes to |
| GET | `/deferred/overdue` | Lots past the term the state allows, which the office has to chase |
| GET | `/deferred/position/{party_code}/{commodity_code}` | One producer's deferred position in a commodity |

### Sales (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/sales` | List sale contracts |
| POST | `/sales` | Write a sale contract |
| GET | `/sales/position/{commodity_code}` | Bushels sold and not yet loaded, which is what makes the house short |
| GET | `/sales/{number}` | Read one sale contract |
| POST | `/sales/{number}/shipments` | Load a shipment against a sale |
| POST | `/sales/{number}/shipments/{reference}/rejection` | Record a rejected shipment |
| POST | `/sales/{number}/shipments/{reference}/settlement` | Settle a shipment on its destination weight and grade |

### Positions (`read`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/positions/{as_of}` | The daily position |
| GET | `/positions/{as_of}/board` | The position, printed as the board |
| GET | `/positions/{as_of}/commodity/{commodity_code}` | One commodity's position |
| GET | `/positions/{as_of}/export` | The position as a CSV attachment |
| GET | `/positions/{as_of}/hedge-sheet` | The hedge sheet, printed |
| GET | `/positions/{as_of}/unhedged` | The unhedged gap across the whole position |

### Desk (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/desk/basis` | Cash less futures, for the board out front |
| GET | `/desk/carry` | Whether the spread pays to hold the grain rather than sell it |
| POST | `/desk/exposure` | Open gain or loss per month against the settlements handed in |
| POST | `/desk/sheet` | The morning sheet: position against broker, one line per commodity |
| POST | `/desk/sheet/actionable` | Only the lines worth a telephone call, for the screen in the office |

### Ledger (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/ledger/accounts` | The filed chart of accounts, in code order |
| GET | `/ledger/journal` | The journal across a span, oldest first |
| GET | `/ledger/journal/entries/{number}` | Read one journal entry |
| GET | `/ledger/journal/source/{source}/{reference}` | Every entry one document raised, the reversals included |
| GET | `/ledger/periods` | The accounting periods, or only the open ones |
| GET | `/ledger/periods/{code}` | What closing this month would find, without closing it |
| POST | `/ledger/periods/{code}/close` | Close a month |
| POST | `/ledger/periods/{code}/reopen` | Open a closed month again |
| GET | `/ledger/trial-balance` | The books as at a day, or across a span if `since` is given |
| POST | `/ledger/years/{year}` | Put a year of open months on file |

### Complaints (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/complaints` | Every open complaint, oldest first |
| POST | `/complaints` | File a complaint, checking a named ticket against the ticket file |
| GET | `/complaints/reports/indefensible` | Open complaints with no retained sample left to argue from |
| GET | `/complaints/reports/repeat-bins` | Bins complained about more often than the house is comfortable with |
| GET | `/complaints/reports/summary` | The page the manager looks at once a month |
| GET | `/complaints/{reference}` | Read one complaint |
| POST | `/complaints/{reference}/actions` | Record what was done about it |
| POST | `/complaints/{reference}/close` | Close a complaint |

### Reports (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/reports/patronage` | The patronage table, one line per producer who settled |
| GET | `/reports/patronage/allocation` | What each member would get out of a pool, before the board votes |
| GET | `/reports/patronage/export` | The patronage table as CSV |
| GET | `/reports/patronage/table` | The patronage page for the board pack, eighty columns wide |

### Operations (`office`)

| method | path | what it does |
| --- | --- | --- |
| GET | `/ops/audit/day/{day}` | The audit trail for one day |
| GET | `/ops/audit/documents/{document_kind}/{document_number}` | Everything that ever happened to one document, oldest first |
| GET | `/ops/audit/party/{party_code}` | A producer's trail, newest first |
| POST | `/ops/close/{day}` | Close a day: the accrual commits, the checks write nothing |
| GET | `/ops/close/{day}/sheet` | The day's close, printed |

### Service (open, no key)

| method | path | what it does |
| --- | --- | --- |
| GET | `/health` | Liveness. Says nothing about the database on purpose |
| GET | `/ready` | Readiness, and the revision the schema is at |
| GET | `/metrics` | What this process has done since it started |
| GET | `/openapi.json` | The API document, OpenAPI 3.1 |
| GET | `/docs` | The API document, rendered for trying routes out |
| GET | `/docs/oauth2-redirect` | Where the interactive document's sign in comes back to |
| GET | `/redoc` | The API document, rendered for reading |

## Tests

    ./gradlew test

The suite is example based where the office would tie the number out by hand,
and runs a few hundred seeded random cases for each rule that has to hold for
every input. The end to end test works one producer through four loads of a
harvest day and asserts the figures a grain accountant would check. The route
tests drive the whole web layer over a real database, and the command line
tests run each command against one.

## Migrations

The schema is built by the Flyway migrations in
`src/main/resources/db/migration`, `V1` to `V9`, and each has a script under
`src/main/resources/db/rollback` that takes it back out. `init-db` applies them;
nothing migrates when the service starts, and `/ready` reports the revision the
schema is at. The schema the migrations build and the one the entities describe
are compared against each other in the test suite. A table added to one and
forgotten in the other fails there rather than on the office box on a Monday.
