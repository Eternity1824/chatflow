# Scenario 2: two server instances + two consumers
server_count   = 2
consumer_count = 2

# Increase consumer concurrency to match higher ingress
consumer_threads             = 40
consumer_prefetch            = 160
consumer_room_max_inflight   = 12
consumer_global_max_inflight = 1000
