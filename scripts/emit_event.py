"""
PARAG Day 4 event emitter.

Publishes DOC_UPDATED, ACL_CHANGED, and DOC_DELETED events so you can watch the
indexer react to the "make permissions live" paths.

Usage (topic + corpus must already exist; indexer running):
    python emit_event.py acl-revoke      # remove leadership from CONF-3001 -> {3,4} becomes {3}
    python emit_event.py acl-grant       # add all-staff to CONF-3001    -> {3} becomes {3,5}
    python emit_event.py update          # bump CONF-1002 to v2 with new body (re-embeds)
    python emit_event.py delete          # delete JIRA-881
"""
import sys
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


def base_event(event_type: str, document_id: str, version: int) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "event_type": event_type,
        "document_id": document_id,
        "doc_version": version,
        "occurred_at": int(time.time() * 1000),
        "payload": None,
        "acl": None,
    }


def build(kind: str) -> dict:
    if kind == "acl-revoke":
        # CONF-3001 was {finance, leadership}; remove leadership.
        e = base_event("ACL_CHANGED", "CONF-3001", 1)
        e["acl"] = {"allowed_groups": ["finance"]}
        return e
    if kind == "acl-grant":
        # Give CONF-3001 back to finance + all-staff (now the intern could see it!).
        e = base_event("ACL_CHANGED", "CONF-3001", 1)
        e["acl"] = {"allowed_groups": ["finance", "all-staff"]}
        return e
    if kind == "update":
        # Re-index CONF-1002 at v2 with a changed body (this re-embeds).
        e = base_event("DOC_UPDATED", "CONF-1002", 2)
        e["payload"] = {
            "source_type": "confluence",
            "space": "ENG",
            "title": "Runbook: Kafka Consumer Lag (v2)",
            "body": "Updated runbook. Check consumer group lag with kafka-consumer-groups "
                    "--describe. Scale consumers up to the partition count; beyond that, "
                    "extra consumers sit idle. New in v2: enable client metrics and alert "
                    "on p99 processing time, not just lag.",
            "allowed_groups": ["engineering"],
        }
        return e
    if kind == "delete":
        return base_event("DOC_DELETED", "JIRA-881", 2)
    raise SystemExit(f"unknown kind: {kind}. Use acl-revoke | acl-grant | update | delete")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    kind = sys.argv[1]
    event = build(kind)

    with open(SCHEMA_PATH) as f:
        schema_str = f.read()
    sr = SchemaRegistryClient({"url": SCHEMA_REGISTRY})
    serializer = AvroSerializer(sr, schema_str)
    key_ser = StringSerializer("utf_8")
    producer = Producer({"bootstrap.servers": BOOTSTRAP})

    producer.produce(
        topic=TOPIC,
        key=key_ser(event["document_id"]),
        value=serializer(event, SerializationContext(TOPIC, MessageField.VALUE)),
    )
    producer.flush()
    print(f"published {event['event_type']} for {event['document_id']} "
          f"(kind={kind})")
    if event["acl"]:
        print(f"  new allowed_groups: {event['acl']['allowed_groups']}")


if __name__ == "__main__":
    main()
