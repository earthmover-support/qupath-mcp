# Security

The endpoint runs arbitrary Groovy as your user, and any local process can call it.

Protections:

- It is off unless `qupath.driver.port` is set. With only `qupath.driver.script` set, QuPath runs that file and opens no port.
- It listens on 127.0.0.1 only.
- It rejects any request that carries an `Origin` header or a `Host` other than `127.0.0.1` or `localhost`. A web page in your browser cannot reach it, even through DNS rebinding, where a page points its own hostname at 127.0.0.1 to look local. `curl -H "Origin: http://evil.example"` returns 403 (checked 2026-10-08).

Use it for testing only. Never ship the jar to end users.
