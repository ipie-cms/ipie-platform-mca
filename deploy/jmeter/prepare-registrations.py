#!/usr/bin/env python3
"""Pre-create confirmed registrations, ready for the `complete` step, and write their ids to CSV.

Why this exists: ipie-registration-load-test.jmx drives the whole registration flow, and each
iteration waits for an OTP email to be delivered asynchronously. That wait dominates the iteration,
so however many threads are configured, only a handful are ever inside the one call that crosses
into ipie-iam-service - and InterServiceClient's Bulkhead (10 concurrent per target) is never
approached. The flow test proves the path works end to end; it cannot find the ceiling.

Splitting the flow fixes that. This script does the slow part up front, in parallel, and leaves a
CSV of registration ids that are confirmed and awaiting nothing but `complete`. A plan reading that
CSV then fires only the crossing call, so concurrency at that call is whatever the thread count
says it is.

    python3 prepare-registrations.py --count 60 --out ready.csv

Prerequisites are the same as the flow plan's (see README.md): the registration rate limit raised
for the duration, and RabbitMQ wired so the OTP mail is actually delivered.
"""
import argparse
import csv
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def post(url, payload=None, timeout=30):
    data = json.dumps(payload).encode() if payload is not None else b''
    request = urllib.request.Request(url, data=data, method='POST',
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read()
        return response.status, (json.loads(body) if body else None)


def get(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read())


def one(index, app, mail, deadline_s):
    """Create -> request OTP -> read it from MailHog -> confirm. Returns a registration id."""
    unique = f'{int(time.time() * 1000) % 10**7}{index:03d}'
    email = f'bulk{unique}@ipie.gov.in'
    mobile = f'+91 9{unique[-9:]:>09}'

    status, body = post(f'{app}/api/v1/registrations',
                        {'mobileNumber': mobile, 'email': email, 'notificationChannels': ['EMAIL']})
    registration_id = body['registrationId']
    post(f'{app}/api/v1/registrations/{registration_id}/email-otp')

    # The outbox relay polls every 5s before the event even reaches the mailer, so poll rather than
    # sleeping a fixed amount - a fixed wait is either wrong or wasteful.
    code, waited = None, 0.0
    while waited < deadline_s:
        time.sleep(2)
        waited += 2
        found = get(f'{mail}/api/v2/search?kind=to&query={email}')
        if found.get('items'):
            match = re.search(r'code is:\s*(\d{6})',
                              found['items'][0]['Content']['Body'].replace('=\r\n', ''))
            if match:
                code = match.group(1)
                break
    if code is None:
        raise RuntimeError(f'no OTP delivered for {email} within {deadline_s}s')

    post(f'{app}/api/v1/registrations/{registration_id}/email-otp/confirm', {'code': code})
    return registration_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--count', type=int, default=60)
    parser.add_argument('--app', default='http://localhost:8092')
    parser.add_argument('--mail', default='http://localhost:8025')
    parser.add_argument('--out', default='ready.csv')
    parser.add_argument('--workers', type=int, default=10)
    parser.add_argument('--otp-timeout', type=float, default=90)
    args = parser.parse_args()

    ids, failures = [], []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(one, i, args.app, args.mail, args.otp_timeout): i
                   for i in range(args.count)}
        for future in as_completed(futures):
            try:
                ids.append(future.result())
            except Exception as exc:  # noqa: BLE001 - the reason is printed, not swallowed
                failures.append(str(exc))

    with open(args.out, 'w', newline='', encoding='utf-8') as handle:
        writer = csv.writer(handle)
        for registration_id in ids:
            writer.writerow([registration_id])

    print(f'  prepared {len(ids)}/{args.count} registrations -> {args.out}')
    for failure in failures[:5]:
        print(f'  failed: {failure}')
    return 0 if ids else 1


if __name__ == '__main__':
    sys.exit(main())
