# Scenario 4: four server instances + four consumers
server_count   = 4
consumer_count = 4

# Throughput-oriented tuning
consumer_threads             = 80
consumer_prefetch            = 200
consumer_room_max_inflight   = 16
consumer_global_max_inflight = 2000
