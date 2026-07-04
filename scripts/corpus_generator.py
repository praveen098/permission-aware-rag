"""
PARAG corpus generator.

Publishes a small, hand-crafted corpus of fake Confluence pages and Jira tickets
to the `doc-events` Kafka topic as Avro DOC_CREATED events. The ACL patterns are
intentional:

  - all-staff docs   -> everyone, including Dev the intern
  - engineering docs -> engineers (and leadership)
  - hr / finance docs -> restricted; the intern must NEVER retrieve these

The finance/hr docs are the leak-test targets for Day 8: an intern query that
surfaces any chunk from them is a permission leak.

Usage:
    pip install -r ../requirements.txt      # from repo root requirements
    python corpus_generator.py
"""
import time
import uuid

from confluent_kafka import Producer
from confluent_kafka.schema_registry import SchemaRegistryClient
from confluent_kafka.schema_registry.avro import AvroSerializer
from confluent_kafka.serialization import (
    MessageField,
    SerializationContext,
    StringSerializer,
)

BOOTSTRAP = "localhost:9092"
SCHEMA_REGISTRY = "http://localhost:8081"
TOPIC = "doc-events"
SCHEMA_PATH = "../schemas/doc_event.avsc"


# ---- The corpus. (document_id, source, space, title, allowed_groups, body) ----
CORPUS = [
    (
        "CONF-1001", "confluence", "ENG", "Onboarding: Dev Environment Setup",
        ["engineering", "all-staff"],
        """Welcome to the engineering team. This guide covers local setup.

Install the JDK 17 and Maven. Clone the platform monorepo and run `make bootstrap`
to provision Postgres, Kafka, and the schema registry via docker compose.

Our services communicate over Kafka using Avro schemas managed in the Confluent
Schema Registry. Every event carries a correlation id for tracing across services.

For access to staging, request the `eng-staging` role in the internal IAM portal.
Reimbursement for a home monitor is available; see the finance policy page.""",
    ),
    (
        "CONF-1002", "confluence", "ENG", "Runbook: Kafka Consumer Lag",
        ["engineering"],
        """When consumer lag alerts fire, first check the consumer group offset with
`kafka-consumer-groups --describe`.

Common causes: a slow downstream dependency (the embedding service), a poison
message causing repeated reprocessing, or a rebalance storm from flapping pods.

To recover, scale the consumer deployment horizontally up to the partition count.
Beyond the partition count, extra consumers sit idle - partitions are the unit of
parallelism.""",
    ),
    (
        "CONF-2001", "confluence", "HR", "Parental Leave Policy 2026",
        ["hr", "all-staff"],
        """All full-time employees are eligible for parental leave after 6 months of
tenure.

Primary caregivers receive 26 weeks of paid leave. Secondary caregivers receive
12 weeks. Leave must be requested through the HR portal at least 30 days before
the intended start date where foreseeable.""",
    ),
    (
        "CONF-2002", "confluence", "HR", "Compensation Bands (CONFIDENTIAL)",
        ["hr", "leadership"],
        """This document defines the internal compensation bands and is restricted to
HR and leadership.

Band L4 (Senior Engineer): base 3,200,000 - 4,800,000 per annum plus equity.
Band L5 (Staff Engineer): base 4,800,000 - 6,500,000 per annum plus equity.
Band L6 (Principal): base 6,500,000 - 9,000,000 per annum plus equity.

Off-band offers require VP approval.""",
    ),
    (
        "CONF-3001", "confluence", "FIN", "Q3 Financial Forecast (RESTRICTED)",
        ["finance", "leadership"],
        """Restricted to finance and leadership.

Q3 revenue is projected at 142 crore, up 18% QoQ, driven by three enterprise
telecom renewals. Operating margin is expected to compress to 22% due to the data
center migration one-time costs.

A hiring freeze on non-engineering roles is in effect through end of Q3.""",
    ),
    (
        "JIRA-881", "jira", "ENG", "eSIM download fails with NumberFormatException",
        ["engineering"],
        """Production incident: eSIM downloads for one carrier fail before the SM-SR call.

Root cause: a blank validityPeriod parameter is parsed with Integer.parseInt,
throwing NumberFormatException. Fix: validate and default the field upstream, add
a null/blank guard in the mapper, and add a regression test.

Severity: high. Affected tenant: one enterprise carrier.""",
    ),
    (
        "JIRA-905", "jira", "FIN", "Automate quarterly revenue report (RESTRICTED)",
        ["finance", "leadership"],
        """Restricted to finance. Track the work to automate the quarterly revenue
report currently assembled by hand.

Current spreadsheet pulls from the billing DB and three regional exports. Target:
a scheduled job that produces the board deck figures, including the Q3 numbers in
the forecast page.""",
    ),
]


def load_schema(path: str) -> str:
    with open(path, "r") as f:
        return f.read()


def make_event(doc) -> dict:
    doc_id, source, space, title, allowed, body = doc
    return {
        "event_id": str(uuid.uuid4()),
        "event_type": "DOC_CREATED",
        "document_id": doc_id,
        "doc_version": 1,
        "occurred_at": int(time.time() * 1000),
        "payload": {
            "source_type": source,
            "space": space,
            "title": title,
            "body": body,
            "allowed_groups": allowed,
        },
        "acl": None,
    }


def main() -> None:
    schema_str = load_schema(SCHEMA_PATH)
    sr = SchemaRegistryClient({"url": SCHEMA_REGISTRY})
    avro_serializer = AvroSerializer(sr, schema_str)
    key_serializer = StringSerializer("utf_8")

    producer = Producer({"bootstrap.servers": BOOTSTRAP})

    published = 0
    for doc in CORPUS:
        event = make_event(doc)
        # Key = document_id => per-document ordering within a partition.
        key = key_serializer(event["document_id"])
        value = avro_serializer(
            event, SerializationContext(TOPIC, MessageField.VALUE)
        )
        producer.produce(topic=TOPIC, key=key, value=value)
        published += 1
        print(f"published DOC_CREATED {event['document_id']} "
              f"({event['payload']['space']}, groups={event['payload']['allowed_groups']})")

    producer.flush()
    print(f"\nDone. Published {published} documents to '{TOPIC}'.")
    print("Restricted docs (leak-test targets): CONF-2002, CONF-3001, JIRA-905")


if __name__ == "__main__":
    main()
