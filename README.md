[TOC]

# Introduction
Rabbitmq support mqtt with rabbitmq-mqtt plugin, but not support topic access control and acl
This project implemented a simple rabbitmqtt acl moudule with the help of eredis_pool

relative modification to rabbitmq-mqtt plugin
- Add a new acl_redis erlang gen-server, who will access external redis server and fetch acl rules from it
- Intercept all pub/sub requests, do topic access controll here by CliendId

developed and tested base rabbitmq 3.3.6

# Usege
1. install and start redis server
2  copy binary plugin to your rabbitmq binary path, e.g.
``cp rabbitmqtt-acl-redis/rabbitmq_mqtt/plugins/*.ez  /usr/lib/rabbitmq/lib/rabbitmq_server-3.6.6/plugins/ ``
3. add "redis" and "acl_cmd" section to rabbit mqtt config file,e.g.
```erlang
 {rabbitmq_mqtt,
  [
   {redis,[{pool_size,50},
           {host,"127.0.0.1"},
           {port,6379}]},
   {acl_cmd, "HGETALL mqtt_acl:~c"}
  ]},
```
4. restart rabbitmq server
5. config your acl rules to redis
access right meaning as below
1:subscribe
2:publish
3:both subsribe and publish
for example, you can config the redis like this corresponding to upper rabbit acl_cmd configuration
```
HSET mqtt_acl:00100001 topic1 1
HSET mqtt_acl:00100001 topic2 2
HSET mqtt_acl:00100001 topic3 3
```

# How to build development env
## Prepare [rabbitmq-public-umbrella](https://github.com/rabbitmq/rabbitmq-public-umbrella)
```
git clone https://github.com/rabbitmq/rabbitmq-public-umbrella
cd rabbitmq-public-umbrella
git checkout rabbitmq_v3_6_6
make co
```
## Patch modification
- replace deps/rabbitmq_mqtt with rabbitmqtt-acl-redis/rabbitmq_mqtt
 you can also refer to rabbitmqtt-acl-redis/rabbitmq_mqtt/acl_redis.diff file for detailed diff with rabbitmq_mqtt master
- replace deps/eredus_pool with rabbitmqtt-acl-redis/eredis_pool
there is also a diff file for you to check

## Build etc
```
cd deps/rabbitmq_mqtt
make   #build only
make run-broker #start rabbitmq broker with mqtt plugin
make dist #genenate releaseable ez file under ./plugin
```

# Test

simple test via mosquitto client are executed

acl rule in redis
```
127.0.0.1:6379> hgetall mqtt_acl:00100001
1) "topic1"
2) "1"
3) "topic2"
4) "2"
5) "topic3"
6) "3"
127.0.0.1:6379> hgetall mqtt_acl:00100002
1) "topic1"
2) "3"
3) "topic2"
4) "3"
5) "topic3"
6) "3"
```

test cases

name|client A|client B|expect|result
----|----|----|----|----
sub allow| ```mosquitto_sub -p 1883 -i 00100001 -t topic1 ```|```mosquitto_pub -p 1883 -i 00100002 -t topic1 -m test-message```| A recevie message from B| pass
sub deny| ```mosquitto_sub -p 1883 -i 00100001 -t topic2 ```|```mosquitto_pub -p 1883 -i 00100002 -t topic2 -m test-message```| A cannot recevie message from B | pass
pub allow| ```mosquitto_pub -p 1883 -i 00100001 -t topic2 -m test-message ```|```mosquitto_sub -p 1883 -i 00100002 -t topic2```|B recevie message from A| pass
pub deny| ```mosquitto_pub -p 1883 -i 00100001 -t topic1 -m test-message ```|```mosquitto_sub -p 1883 -i 00100002 -t topic1```|B cannot recevie message from A| pass


