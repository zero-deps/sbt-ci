**release server**
====

- fetch deps
- compile
- make projects
- make releases
- configure releases
- commit/push
- ci web pages
- rest api
- deploy

**external api**
---
gitlab api endpoint
authentication

POST /authorization
{token,_}
POST /auth/gitlab
{access_token,_}
DELETE authorization/1

## entities
* branches
* builds
* jobs
* logs GET /logs/{log.id}
streaming logs. subscribe to pusher channel (job-id)
* settings
