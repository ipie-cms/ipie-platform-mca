# Sample service-to-service authorization policy for common-client's OpaAuthorizationInterceptor
# (ipie.client.security.opa.enabled=true) - a starting point to adapt per deployment, not a fixed
# platform policy. Queried as POST /v1/data/ipie/interservice/allow with:
#   {"input": {"caller": "<calling-service>", "target": "<target-service>", "method": "GET", "path": "/api/v1/..."}}
# and expects the standard OPA Data API response shape {"result": true|false}.
#
# This is the inter-service trust matrix (Development_Environment_Configuration.md, Section 15:
# "maintain an inter-service trust matrix... which service can call which, over what protocol,
# with what auth") expressed as an enforceable policy rather than only a document - keep this file
# and the actual trust-matrix document in sync as services are added.
package ipie.interservice

import rego.v1

# allowed_calls[caller] is the set of target services that caller may reach at all.
allowed_calls := {
	"iam-service": {"audit-service", "notification-service"},
	"notification-service": {"audit-service"},
	"audit-service": set(),
}

# admin_only_paths are path prefixes on the *target* that only a fixed allow-list of trusted
# callers may reach, even if allowed_calls above permits the call generally - e.g. the
# Notification service can call IAM for ordinary lookups, but never IAM's admin endpoints.
admin_only_paths := ["/api/v1/admin"]

admin_allowed_callers := {"iam-service"}

default allow := false

allow if {
	# The general caller -> target reachability check.
	input.target in allowed_calls[input.caller]

	# And, if the path is admin-only, the caller must also be in the trusted admin-caller list.
	not is_admin_path
}

allow if {
	input.target in allowed_calls[input.caller]
	is_admin_path
	input.caller in admin_allowed_callers
}

is_admin_path if {
	some prefix in admin_only_paths
	startswith(input.path, prefix)
}
