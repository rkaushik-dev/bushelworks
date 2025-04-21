"""Every route the application answers on, written down.

A route is a promise. The scale terminal, the office screen and the producer
portal all have this list compiled into them, and a path that quietly changes
shape breaks a client nobody is going to redeploy this week.

So the surface is a constant here and a test compares it. Adding a route means
adding a line, which is the point: it is one line of thought about whether the
path is the one you want to live with.

Order matters as much as content. FastAPI matches in declaration order, and a
``/{reference}`` declared before ``/reports/summary`` swallows it. That has
happened twice here, so the list is sorted by path and the test checks the real
matching order separately.
"""

ROUTES = (
    ("GET", "/bins/{location_code}"),
    ("POST", "/bins/{location_code}/loads"),
    ("GET", "/bins/{location_code}/report/export"),
    ("GET", "/bins/{location_code}/report/sheet"),
    ("GET", "/bins/{location_code}/report/summary"),
    ("GET", "/bins/{location_code}/{bin_id}"),
    ("POST", "/bins/{location_code}/{bin_id}/draws"),
    ("POST", "/bins/{location_code}/{bin_id}/out-of-service"),
    ("POST", "/bins/{location_code}/{bin_id}/readings"),
    ("GET", "/complaints"),
    ("POST", "/complaints"),
    ("GET", "/complaints/reports/indefensible"),
    ("GET", "/complaints/reports/repeat-bins"),
    ("GET", "/complaints/reports/summary"),
    ("GET", "/complaints/{reference}"),
    ("POST", "/complaints/{reference}/actions"),
    ("POST", "/complaints/{reference}/close"),
    ("GET", "/contracts"),
    ("POST", "/contracts"),
    ("GET", "/contracts/{number}"),
    ("POST", "/contracts/{number}/basis"),
    ("POST", "/contracts/{number}/cancel"),
    ("POST", "/contracts/{number}/futures"),
    ("POST", "/contracts/{number}/roll"),
    ("GET", "/deferred/exposure/{commodity_code}"),
    ("GET", "/deferred/lots"),
    ("POST", "/deferred/lots"),
    ("POST", "/deferred/lots/{lot_id}/pricing"),
    ("GET", "/deferred/overdue"),
    ("GET", "/deferred/position/{party_code}/{commodity_code}"),
    ("GET", "/desk/basis"),
    ("GET", "/desk/carry"),
    ("POST", "/desk/exposure"),
    ("POST", "/desk/sheet"),
    ("POST", "/desk/sheet/actionable"),
    ("GET", "/docs"),
    ("GET", "/docs/oauth2-redirect"),
    ("GET", "/health"),
    ("GET", "/metrics"),
    ("GET", "/openapi.json"),
    ("GET", "/ops/audit/day/{day}"),
    ("GET", "/ops/audit/documents/{document_kind}/{document_number}"),
    ("GET", "/ops/audit/party/{party_code}"),
    ("POST", "/ops/close/{day}"),
    ("GET", "/ops/close/{day}/sheet"),
    ("GET", "/parties"),
    ("POST", "/parties"),
    ("GET", "/parties/farms/{farm_code}/agreement"),
    ("POST", "/parties/farms/{farm_code}/agreements"),
    ("GET", "/parties/{code}"),
    ("POST", "/parties/{code}/checkoff-exemption"),
    ("POST", "/parties/{code}/closure"),
    ("GET", "/parties/{code}/farms"),
    ("GET", "/positions/{as_of}"),
    ("GET", "/positions/{as_of}/board"),
    ("GET", "/positions/{as_of}/commodity/{commodity_code}"),
    ("GET", "/positions/{as_of}/export"),
    ("GET", "/positions/{as_of}/hedge-sheet"),
    ("GET", "/positions/{as_of}/unhedged"),
    ("GET", "/ready"),
    ("GET", "/redoc"),
    ("GET", "/reports/patronage"),
    ("GET", "/reports/patronage/allocation"),
    ("GET", "/reports/patronage/export"),
    ("GET", "/reports/patronage/table"),
    ("GET", "/sales"),
    ("POST", "/sales"),
    ("GET", "/sales/position/{commodity_code}"),
    ("GET", "/sales/{number}"),
    ("POST", "/sales/{number}/shipments"),
    ("POST", "/sales/{number}/shipments/{reference}/rejection"),
    ("POST", "/sales/{number}/shipments/{reference}/settlement"),
    ("GET", "/settlements"),
    ("POST", "/settlements"),
    ("GET", "/settlements/register/{on}"),
    ("GET", "/settlements/{number}"),
    ("POST", "/settlements/{number}/payment"),
    ("GET", "/settlements/{number}/statement"),
    ("POST", "/storage/accruals"),
    ("GET", "/storage/lots"),
    ("POST", "/storage/lots"),
    ("GET", "/storage/lots/{lot_id}"),
    ("GET", "/storage/on-hand/{commodity_code}"),
    ("POST", "/storage/receipts"),
    ("POST", "/storage/receipts/{receipt_number}/cancel"),
    ("POST", "/storage/withdrawals"),
    ("GET", "/tickets"),
    ("POST", "/tickets"),
    ("POST", "/tickets/batch"),
    ("GET", "/tickets/{location_code}/{ticket_number}"),
    ("GET", "/tickets/{location_code}/{ticket_number}/grade"),
    ("POST", "/tickets/{location_code}/{ticket_number}/void"),
)


# Paths that answer without a key, because a health check that gets a 401 takes
# the box out of the load balancer.
OPEN_PATHS = (
    "/health",
    "/ready",
    "/metrics",
    "/openapi.json",
    "/docs",
    "/docs/oauth2-redirect",
    "/redoc",
)
