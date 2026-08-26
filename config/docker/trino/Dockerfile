# Trino's Iceberg connector is built in — no custom plugin build needed
# (unlike the Paimon path this repo originally tried; see the design doc's
# Alternatives Considered for why that was abandoned). This Dockerfile
# exists only so docker-compose.trino.yml's `build:` stanza has a stable
# target; it adds nothing to the stock image.
FROM trinodb/trino:470
