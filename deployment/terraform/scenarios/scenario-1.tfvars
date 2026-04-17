# Scenario 1: single instance baseline
server_count   = 1
consumer_count = 1

# Keep tuning conservative for baseline measurement
consumer_threads             = 20
consumer_prefetch            = 120
consumer_room_max_inflight   = 8
consumer_global_max_inflight = 500
