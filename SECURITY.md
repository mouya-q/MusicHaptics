# Security

Root access is optional and is used for hardware discovery or validated direct-drive paths.

Direct-drive writes are restricted to validated `/sys/` and `/dev/` nodes and range-checked values.

Do not execute package names, audio data, or other untrusted input as shell commands.
