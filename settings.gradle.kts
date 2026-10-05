rootProject.name = "paypen"

include("ledger", "common", "directory", "bank", "hub")
include("integration-tests") // runs all four services together
