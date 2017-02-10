[TOC]

# rabbitmqtt-acl-redis
branch from rabbitmqtt plugin, add topic access acl control with redis

# Introduction
Rabbitmq support mqtt with rabbitmq-mqtt plugin, however not support topic access control and acl, here we refer the source of emqtt acl part and implement a simple acl moudule with external redis

Modification of rabbitmq-mqtt plugin
- Add a new acl_redis erlang gen-server, who will access external redis server and fetch acl rules from it
- - Intercept all pub/sub requests, do topic access controll here by CliendId
-
- developed and tested base rabbitmq 3.3.6
-
- # how to use
-
- copy 
-
-
- # software development env
- - [rabbitmq-public-umbrella](https://github.com/rabbitmq/rabbitmq-public-umbrella)
