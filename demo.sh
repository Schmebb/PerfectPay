#!/usr/bin/env bash
# Walks through the happy path and the main failure scenarios against a running PayPen,
# printing balances along the way. Start everything first with `docker compose up --build`
# (or the four java -jar commands in the README), then run ./demo.sh.
#
# Safe to run more than once: payment ids are unique per run, and balances carry over.
set -euo pipefail

DIRECTORY=${DIRECTORY:-http://localhost:8090}
HUB=${HUB:-http://localhost:8080}
BANK_A=${BANK_A:-http://localhost:8081}
BANK_B=${BANK_B:-http://localhost:8082}
RUN=$(date +%s)

THANDI_PHONE=0821234567   # BANK_A
SIPHO_PHONE=0831234567    # BANK_B
BONGANI_PHONE=0731234567  # BANK_B, frozen

bold=$(tput bold 2>/dev/null || true)
dim=$(tput dim 2>/dev/null || true)
reset=$(tput sgr0 2>/dev/null || true)

# --- helpers ------------------------------------------------------------------------------

# Pull a field out of flat JSON. Good enough for this project's responses; not a JSON parser.
json_str() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
json_num() { sed -n "s/.*\"$1\":\(-\{0,1\}[0-9]*\).*/\1/p"; }

rand() { awk -v c="$1" 'BEGIN { s = c < 0 ? "-" : ""; c = c < 0 ? -c : c; printf "%sR%d.%02d", s, int(c / 100), c % 100 }'; }

balance() { curl -sf "$1/accounts/$2" | json_num balanceCents; }

post() { curl -sf -H 'Content-Type: application/json' -X "${3:-POST}" -d "$2" "$1"; }

pay() { # bank from toPhone amountCents name
    post "$1/customer-payments" \
        "{\"paymentId\":\"$RUN-$5\",\"fromAccount\":\"$2\",\"toPhone\":\"$3\",\"amountCents\":$4}"
}

show_result() {
    local status reason
    status=$(echo "$1" | json_str status)
    reason=$(echo "$1" | json_str reason)
    echo "  → ${bold}${status}${reset}${reason:+  ($reason)}"
}

show_balances() {
    printf "  ${dim}%-28s %10s${reset}\n" "account" "balance"
    for entry in "$BANK_A thandi" "$BANK_A OUTGOING_SUSPENSE" "$BANK_A SCHEME_SETTLEMENT" \
                 "$BANK_B sipho" "$BANK_B SCHEME_SETTLEMENT"; do
        set -- $entry
        local bank=BANK_A
        [[ $1 == "$BANK_B" ]] && bank=BANK_B
        printf "  %-28s %10s\n" "$bank $2" "$(rand "$(balance "$1" "$2")")"
    done
}

step() { echo; echo "${bold}== $1${reset}"; }

wait_for() {
    printf "Waiting for %s " "$1"
    for _ in $(seq 1 90); do
        if curl -sf "$2/actuator/health" >/dev/null 2>&1; then echo "✓"; return; fi
        printf "."; sleep 1
    done
    echo; echo "$1 at $2 isn't answering. Is everything running?" >&2; exit 1
}

# --- the demo -------------------------------------------------------------------------------

wait_for directory "$DIRECTORY"
wait_for hub "$HUB"
wait_for "bank A" "$BANK_A"
wait_for "bank B" "$BANK_B"
printf "Waiting for phone numbers to be registered "
until curl -sf "$DIRECTORY/proxies/$SIPHO_PHONE" >/dev/null && curl -sf "$DIRECTORY/proxies/$THANDI_PHONE" >/dev/null; do
    printf "."; sleep 1
done
echo "✓"

post "$BANK_B/admin/chaos" '{"mode":"NONE","delayMs":0}' PUT >/dev/null

step "Starting balances"
show_balances

step "1. Happy path: Thandi (BANK_A) pays Sipho (BANK_B) R120 by phone number"
show_result "$(pay "$BANK_A" thandi "$SIPHO_PHONE" 12000 happy)"
show_balances

step "2. Duplicate: Thandi's app retries the same payment"
show_result "$(pay "$BANK_A" thandi "$SIPHO_PHONE" 12000 happy)"
echo "  Same answer, nothing moved twice:"
show_balances

step "3. Unknown number: nobody has 0611111111"
show_result "$(pay "$BANK_A" thandi 0611111111 1000 unknown)"

step "4. Insufficient funds: Thandi tries to send R10,000"
show_result "$(pay "$BANK_A" thandi "$SIPHO_PHONE" 1000000 broke)"

step "5. Frozen payee: Bongani's account at BANK_B is frozen"
show_result "$(pay "$BANK_A" thandi "$BONGANI_PHONE" 5000 frozen)"
echo "  Held, sent, rejected by BANK_B, refunded. Suspense is back to zero:"
show_balances

step "6. Over the scheme limit: Thandi deposits R5,000, then tries to send R3,500"
post "$BANK_A/admin/accounts/thandi/deposit" '{"amountCents":500000}' >/dev/null
show_result "$(pay "$BANK_A" thandi "$SIPHO_PHONE" 350000 limit)"

step "7. Lost response: BANK_B credits Sipho, then its answer never arrives"
post "$BANK_B/admin/chaos" '{"mode":"DROP_RESPONSE","delayMs":8000}' PUT >/dev/null
sipho_before=$(balance "$BANK_B" sipho)
result=$(pay "$BANK_A" thandi "$SIPHO_PHONE" 5000 lost)
show_result "$result"
echo "  BANK_A doesn't refund on a timeout; the R50 waits in suspense:"
show_balances
printf "  Waiting for the hub to retry and BANK_A's enquiry job to find out what happened "
for _ in $(seq 1 60); do
    status=$(curl -sf "$BANK_A/customer-payments/$RUN-lost" | json_str status)
    [[ $status == COMPLETED || $status == REFUNDED ]] && break
    printf "."; sleep 1
done
echo
echo "  → ${bold}${status}${reset}"
echo "  Sipho was credited exactly once: $(rand "$sipho_before") → $(rand "$(balance "$BANK_B" sipho)")"
show_balances
post "$BANK_B/admin/chaos" '{"mode":"NONE","delayMs":0}' PUT >/dev/null

step "Settlement: who owes whom today"
curl -sf "$HUB/settlement" | grep -o '"summary":"[^"]*"' | sed 's/"summary":"\(.*\)"/  \1/' || echo "  (nothing to settle)"

step "Books check: every ledger sums to zero"
for bank in "$BANK_A" "$BANK_B"; do
    ledger=$(curl -sf "$bank/admin/ledger")
    echo "  $(echo "$ledger" | json_str bankCode): total $(rand "$(echo "$ledger" | json_num totalCents)")"
done
echo
