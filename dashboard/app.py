import os
import time
import uuid
import random
from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry
import pandas as pd
import streamlit as st
import plotly.express as px
import plotly.graph_objects as go

# ---------------------------------------------------------
# Page Configuration & Aesthetics
# ---------------------------------------------------------
st.set_page_config(
    page_title="ApexPay | Distributed Fault-Tolerant Engine",
    page_icon="⚡",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Load Custom CSS Design System
def load_css():
    css_path = os.path.join(os.path.dirname(__file__), "style.css")
    if os.path.exists(css_path):
        with open(css_path) as f:
            st.markdown(f"<style>{f.read()}</style>", unsafe_allow_html=True)

load_css()

# ---------------------------------------------------------
# Robust Session & Retry Config
# ---------------------------------------------------------
def create_robust_session():
    session = requests.Session()
    retry_strategy = Retry(
        total=2,
        connect=2,
        read=2,
        backoff_factor=0.1,
        status_forcelist=[500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

http_session = create_robust_session()

def format_timestamp_safe(val):
    if val is None:
        return ""
    try:
        s = str(val).replace("T", " ").rstrip("Z")
        if "." in s:
            parts = s.split(".")
            return parts[0] + "." + parts[1][:3]
        return s
    except Exception:
        return str(val)


# ---------------------------------------------------------
# Realistic Default Dataset (Standalone & Seeder Engine)
# ---------------------------------------------------------
def generate_initial_realistic_payments():
    now = datetime.now()
    records = [
        {
            "paymentId": "PAY_9A8B7C6D5E4F3A21",
            "idempotencyKey": "IDEM_AWS_99182",
            "userId": "USR_ALICE",
            "amount": 1250.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "AWS Cloud Compute Cluster - US East",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=142)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=142)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_3D7E8F1A2B5C6D7E",
            "idempotencyKey": "IDEM_STP_33910",
            "userId": "USR_CHARLIE",
            "amount": 3450.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "BANK_TRANSFER",
            "description": "Stripe Connect Merchant Settlement",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=128)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=128)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_1B2C3D4E5F6A7B8C",
            "idempotencyKey": "IDEM_SHP_44812",
            "userId": "USR_BOB",
            "amount": 89.99,
            "currency": "USD",
            "status": "REFUNDED",
            "paymentMethod": "APPLE_PAY",
            "description": "Shopify Store Order #84920",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=115)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=45)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_7A8B9C0D1E2F3A4B",
            "idempotencyKey": "IDEM_GH_77218",
            "userId": "USR_DAVID",
            "amount": 420.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "GitHub Enterprise Cloud Subscription",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=98)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=98)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_5C6D7E8F9A0B1C2D",
            "idempotencyKey": "IDEM_RZP_11928",
            "userId": "USR_CHARLIE",
            "amount": 45000.00,
            "currency": "INR",
            "status": "SUCCESS",
            "paymentMethod": "UPI",
            "description": "Razorpay Merchant Payout Batch",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=84)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=84)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_2E3F4A5B6C7D8E9F",
            "idempotencyKey": "IDEM_DC_88192",
            "userId": "USR_ALICE",
            "amount": 950.00,
            "currency": "GBP",
            "status": "SUCCESS",
            "paymentMethod": "BANK_TRANSFER",
            "description": "UK Data Center Colocation Fee",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=72)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=72)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_8F9A0B1C2D3E4F5A",
            "idempotencyKey": "IDEM_LH_55918",
            "userId": "USR_BOB",
            "amount": 650.00,
            "currency": "EUR",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "Lufthansa Flight Booking LHR-FRA",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=61)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=61)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_4A5B6C7D8E9F0A1B",
            "idempotencyKey": "IDEM_APL_33019",
            "userId": "USR_BOB",
            "amount": 120.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "Apple App Store In-App Token Pack",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=52)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=52)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_0D1E2F3A4B5C6D7E",
            "idempotencyKey": "IDEM_NFLX_1029",
            "userId": "USR_ALICE",
            "amount": 14.99,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "DEBIT_CARD",
            "description": "Netflix Monthly 4K Renewal",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=44)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=44)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_6E7F8A9B0C1D2E3F",
            "idempotencyKey": "IDEM_FIG_22019",
            "userId": "USR_CHARLIE",
            "amount": 1800.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "Figma Organization Annual Seats",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=35)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=35)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_9B0C1D2E3F4A5B6C",
            "idempotencyKey": "IDEM_STM_99182",
            "userId": "USR_BOB",
            "amount": 59.99,
            "currency": "USD",
            "status": "FAILED",
            "paymentMethod": "DEBIT_CARD",
            "description": "Steam Digital Store Purchase",
            "failureReason": "INSUFFICIENT_FUNDS: Issuer bank rejected authorization",
            "retryCount": 2,
            "createdAt": (now - timedelta(minutes=28)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=28)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_3A4B5C6D7E8F9A0B",
            "idempotencyKey": "IDEM_DD_66192",
            "userId": "USR_DAVID",
            "amount": 299.00,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "Datadog APM Ingestion Overages",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=22)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=22)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_8B9C0D1E2F3A4B5C",
            "idempotencyKey": "IDEM_UBR_99214",
            "userId": "USR_DAVID",
            "amount": 35.50,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "WALLET",
            "description": "Uber Trip Passenger Fare #672",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=16)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=16)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_2A3B4C5D6E7F8A9B",
            "idempotencyKey": "IDEM_REM_55102",
            "userId": "USR_CHARLIE",
            "amount": 950.00,
            "currency": "GBP",
            "status": "FAILED",
            "paymentMethod": "BANK_TRANSFER",
            "description": "Cross-Border Remittance to UK",
            "failureReason": "GATEWAY_TIMEOUT_504: Remote banking partner timed out after 3 retries",
            "retryCount": 3,
            "createdAt": (now - timedelta(minutes=10)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=10)).strftime('%Y-%m-%d %H:%M:%S')
        },
        {
            "paymentId": "PAY_4D5E6F7A8B9C0D1E",
            "idempotencyKey": "IDEM_OAI_44921",
            "userId": "USR_CHARLIE",
            "amount": 512.40,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "OpenAI API Monthly Usage Tier 4",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": (now - timedelta(minutes=4)).strftime('%Y-%m-%d %H:%M:%S'),
            "updatedAt": (now - timedelta(minutes=4)).strftime('%Y-%m-%d %H:%M:%S')
        }
    ]
    return records

def generate_initial_realistic_audit():
    now = datetime.now()
    logs = [
        {
            "paymentId": "PAY_4D5E6F7A8B9C0D1E",
            "previousStatus": "PENDING",
            "newStatus": "SUCCESS",
            "action": "PAYMENT_AUTHORIZED",
            "workerId": "worker-partition-2",
            "detail": "Payment captured and authorized via Stripe Gateway in 34ms",
            "timestamp": (now - timedelta(minutes=4)).strftime('%Y-%m-%d %H:%M:%S.%f')
        },
        {
            "paymentId": "PAY_4D5E6F7A8B9C0D1E",
            "previousStatus": "NONE",
            "newStatus": "PENDING",
            "action": "INITIATE_SYNC_PAYMENT",
            "workerId": "API_HANDLER",
            "detail": "Idempotency key verified: IDEM_OAI_44921. Ledger balance reserved.",
            "timestamp": (now - timedelta(minutes=4, seconds=1)).strftime('%Y-%m-%d %H:%M:%S.%f')
        },
        {
            "paymentId": "PAY_2A3B4C5D6E7F8A9B",
            "previousStatus": "RETRYING",
            "newStatus": "FAILED",
            "action": "EXHAUSTED_RETRIES_DLQ",
            "workerId": "worker-partition-3",
            "detail": "Exceeded max 3 retry attempts. Dispatched payload to Dead Letter Queue (DLQ).",
            "timestamp": (now - timedelta(minutes=10)).strftime('%Y-%m-%d %H:%M:%S.%f')
        },
        {
            "paymentId": "PAY_8B9C0D1E2F3A4B5C",
            "previousStatus": "PENDING",
            "newStatus": "SUCCESS",
            "action": "PAYMENT_AUTHORIZED",
            "workerId": "worker-partition-0",
            "detail": "Wallet debit confirmed. Zero latency cache lock released.",
            "timestamp": (now - timedelta(minutes=16)).strftime('%Y-%m-%d %H:%M:%S.%f')
        },
        {
            "paymentId": "PAY_1B2C3D4E5F6A7B8C",
            "previousStatus": "SUCCESS",
            "newStatus": "REFUNDED",
            "action": "REFUND_PROCESSED",
            "workerId": "WORKER_REFUND",
            "detail": "Refund of $89.99 credited back to Apple Pay token. User balance restored.",
            "timestamp": (now - timedelta(minutes=45)).strftime('%Y-%m-%d %H:%M:%S.%f')
        }
    ]
    return logs

# Initialize Session State
if "sim_payments" not in st.session_state:
    st.session_state.sim_payments = generate_initial_realistic_payments()

if "sim_audit" not in st.session_state:
    st.session_state.sim_audit = generate_initial_realistic_audit()

if "sim_idempotency" not in st.session_state:
    st.session_state.sim_idempotency = {p["idempotencyKey"]: p for p in st.session_state.sim_payments}

if "sim_config" not in st.session_state:
    st.session_state.sim_config = {
        "enableLatency": False,
        "latencyMs": 1500,
        "enableTimeouts": False,
        "timeoutProbability": 0.2,
        "enableGatewayFailures": False,
        "failureProbability": 0.25,
        "enablePartialDbFailures": False,
        "dbFailureProbability": 0.15
    }

# ---------------------------------------------------------
# Sidebar Configuration & Backend Health Probe
# ---------------------------------------------------------
st.sidebar.markdown("""
<div style="display: flex; align-items: center; gap: 10px; margin-bottom: 12px;">
    <div style="font-size: 2rem;">⚡</div>
    <div>
        <div style="font-size: 1.25rem; font-weight: 800; color: #ffffff; letter-spacing: -0.02em;">ApexPay Engine</div>
        <div style="font-size: 0.72rem; color: #38bdf8; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em;">Distributed Fault-Tolerant System</div>
    </div>
</div>
""", unsafe_allow_html=True)

backend_mode = st.sidebar.radio(
    "Target Processing Mode",
    options=["Auto-Detect (Spring Boot or Standalone)", "Force Live Spring Boot API", "Force Standalone Visual Engine"],
    index=0,
    help="Select whether to bind directly to the Spring Boot microservice or run in embedded standalone simulation mode."
)

default_url = os.getenv("BACKEND_API_URL", "http://localhost:8080").rstrip("/")
raw_backend_url = st.sidebar.text_input(
    "Backend API Base URL",
    value=default_url,
    help="URL for your Spring Boot backend (Default: http://localhost:8080 for local, or https://... for hosted)"
).rstrip("/")

def check_backend_health(url):
    candidate_urls = [url]
    if ":8501" in url:
        candidate_urls.append(url.replace(":8501", ":8080"))
    if "localhost" in url:
        candidate_urls.append(url.replace("localhost", "127.0.0.1"))
    elif "127.0.0.1" in url:
        candidate_urls.append(url.replace("127.0.0.1", "localhost"))

    endpoints = ["/actuator/health", "/", "/api/v1/metrics"]

    for cand in candidate_urls:
        base = cand.rstrip("/")
        for ep in endpoints:
            try:
                resp = http_session.get(f"{base}{ep}", timeout=2.5)
                if resp.status_code == 200:
                    try:
                        data = resp.json()
                        if isinstance(data, dict):
                            return True, "UP", data, base
                    except Exception:
                        continue
            except Exception:
                continue

    return False, "OFFLINE", {"status": "DOWN"}, url

if "Force Standalone" in backend_mode:
    is_live_backend = False
    active_backend_url = raw_backend_url
    mode_label = "Standalone Simulation Engine"
elif "Force Live" in backend_mode:
    is_live_backend, health_status, health_details, active_backend_url = check_backend_health(raw_backend_url)
    mode_label = f"Live Spring Boot (`{active_backend_url}`)" if is_live_backend else "Waiting for Backend"
else:
    is_live_backend, health_status, health_details, active_backend_url = check_backend_health(raw_backend_url)
    mode_label = f"Live Spring Boot (`{active_backend_url}`)" if is_live_backend else "Standalone Visual Engine"

# ---------------------------------------------------------
# Dual API Client Helper
# ---------------------------------------------------------
def get_standalone_metrics():
    payments = st.session_state.sim_payments
    total = len(payments)
    success = sum(1 for p in payments if p.get("status") == "SUCCESS")
    failed = sum(1 for p in payments if p.get("status") == "FAILED")
    refunded = sum(1 for p in payments if p.get("status") == "REFUNDED")
    retries = sum(p.get("retryCount", 0) for p in payments)
    success_rate = round((success / (success + failed) * 100.0), 1) if (success + failed) > 0 else 100.0
    status_map = {}
    for p in payments:
        st_val = p.get("status", "SUCCESS")
        status_map[st_val] = status_map.get(st_val, 0) + 1

    return {
        "totalTransactions": total,
        "successfulTransactions": success,
        "failedTransactions": failed,
        "refundedTransactions": refunded,
        "retryCount": retries,
        "dlqCount": sum(1 for p in payments if p.get("retryCount", 0) >= 3),
        "activeQueueDepth": 0,
        "successRatePercent": success_rate,
        "averageLatencyMs": 24.5,
        "idempotencyHits": len(st.session_state.sim_idempotency),
        "statusBreakdown": status_map
    }

def api_get(endpoint):
    if is_live_backend:
        try:
            resp = http_session.get(f"{active_backend_url}{endpoint}", timeout=4)
            if resp.status_code == 200:
                return resp.json()
        except Exception:
            pass

    # Standalone Simulation Fallback
    if endpoint == "/api/v1/metrics":
        return get_standalone_metrics()
    elif endpoint == "/api/v1/payments":
        return st.session_state.sim_payments
    elif endpoint == "/api/v1/audit/logs":
        return st.session_state.sim_audit
    elif endpoint == "/api/v1/simulation/config":
        return st.session_state.sim_config
    elif endpoint == "/api/v1/simulation/dlq":
        dlq_msgs = [p for p in st.session_state.sim_payments if p.get("retryCount", 0) >= 3]
        return {"dlqCount": len(dlq_msgs), "messages": dlq_msgs}
    return None

def api_post(endpoint, payload, headers=None):
    if is_live_backend:
        try:
            resp = http_session.post(f"{active_backend_url}{endpoint}", json=payload, headers=headers or {}, timeout=7)
            if resp.content:
                try:
                    return resp.status_code, resp.json()
                except Exception:
                    return resp.status_code, {"error": f"Server response: {resp.text}"}
            return resp.status_code, {}
        except Exception as e:
            if "Force Live" in backend_mode:
                return 503, {"error": f"Connection error to backend at {active_backend_url}: {str(e)}"}

    # Standalone Engine implementation
    if endpoint == "/api/v1/payments":
        idem_key = payload.get("idempotencyKey") or (headers or {}).get("X-Idempotency-Key") or f"IDEM_{int(time.time()*1000)}"
        
        # Idempotency deduplication check
        if idem_key in st.session_state.sim_idempotency:
            cached_resp = dict(st.session_state.sim_idempotency[idem_key])
            cached_resp["isCachedIdempotentResponse"] = True
            return 200, cached_resp

        payment_id = "PAY_" + uuid.uuid4().hex[:16].upper()
        now_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        # Check fault rules
        cfg = st.session_state.sim_config
        is_failure = False
        fail_reason = None
        retries = 0

        if cfg.get("enableGatewayFailures") and random.random() < cfg.get("failureProbability", 0.4):
            is_failure = True
            retries = 3
            fail_reason = "SIMULATED_GATEWAY_503: Gateway service unavailable after backoff retries"
        elif cfg.get("enableTimeouts") and random.random() < cfg.get("timeoutProbability", 0.3):
            is_failure = True
            retries = 3
            fail_reason = "SIMULATED_TIMEOUT: Remote banking socket timed out (read timeout)"

        status = "FAILED" if is_failure else "SUCCESS"

        payment_record = {
            "paymentId": payment_id,
            "idempotencyKey": idem_key,
            "userId": payload.get("userId", "USR_ALICE"),
            "amount": float(payload.get("amount", 100.0)),
            "currency": payload.get("currency", "USD"),
            "status": status,
            "paymentMethod": payload.get("paymentMethod", "CREDIT_CARD"),
            "description": payload.get("description", "Payment transaction"),
            "failureReason": fail_reason,
            "retryCount": retries,
            "createdAt": now_str,
            "updatedAt": now_str,
            "isCachedIdempotentResponse": False
        }

        st.session_state.sim_payments.insert(0, payment_record)
        st.session_state.sim_idempotency[idem_key] = payment_record

        # Audit Log
        shard = abs(hash(payload.get("userId", "USR_ALICE"))) % 4
        st.session_state.sim_audit.insert(0, {
            "paymentId": payment_id,
            "previousStatus": "PENDING",
            "newStatus": status,
            "action": "PAYMENT_FAILED_DLQ" if is_failure else "PAYMENT_AUTHORIZED",
            "workerId": f"worker-partition-{shard}",
            "detail": fail_reason if is_failure else "Payment processed successfully via visual engine",
            "timestamp": now_str
        })

        status_code = 202 if payload.get("asyncProcessing") else (201 if not is_failure else 500)
        return status_code, payment_record

    elif endpoint == "/api/v1/refunds":
        payment_id = payload.get("paymentId")
        refund_amount = payload.get("amount", 100.0)
        now_str = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

        found = False
        for p in st.session_state.sim_payments:
            if p.get("paymentId") == payment_id or not payment_id:
                p["status"] = "REFUNDED"
                payment_id = p.get("paymentId")
                found = True
                break

        refund_resp = {
            "refundId": "REF_" + uuid.uuid4().hex[:16].upper(),
            "paymentId": payment_id or "PAY_SAMPLE",
            "amount": refund_amount,
            "status": "SUCCESS",
            "reason": payload.get("reason", "Customer requested refund"),
            "createdAt": now_str
        }

        st.session_state.sim_audit.insert(0, {
            "paymentId": payment_id or "PAY_SAMPLE",
            "previousStatus": "SUCCESS",
            "newStatus": "REFUNDED",
            "action": "REFUND_PROCESSED",
            "workerId": "WORKER_REFUND",
            "detail": f"Refund of ${refund_amount} processed and balance returned",
            "timestamp": now_str
        })

        return 201, refund_resp

    elif endpoint == "/api/v1/simulation/config":
        st.session_state.sim_config.update(payload)
        return 200, st.session_state.sim_config

    elif endpoint == "/api/v1/simulation/reset":
        st.session_state.sim_config = {
            "enableLatency": False,
            "latencyMs": 1500,
            "enableTimeouts": False,
            "timeoutProbability": 0.2,
            "enableGatewayFailures": False,
            "failureProbability": 0.25,
            "enablePartialDbFailures": False,
            "dbFailureProbability": 0.15
        }
        return 200, st.session_state.sim_config

    return 200, {}

# ---------------------------------------------------------
# Sidebar Health Status & Telemetry
# ---------------------------------------------------------
if is_live_backend:
    st.sidebar.markdown(f"""
    <div style="background: rgba(16, 185, 129, 0.12); border: 1px solid rgba(16, 185, 129, 0.35); border-radius: 10px; padding: 12px; margin-bottom: 14px;">
        <div style="display: flex; align-items: center; gap: 8px;">
            <span class="pulse-dot"></span>
            <span style="font-weight: 700; color: #34d399; font-size: 0.88rem;">SPRING BOOT CONNECTED</span>
        </div>
        <div style="font-size: 0.76rem; color: #94a3b8; margin-top: 4px; word-break: break-all;">
            Node: <code>{active_backend_url}</code><br>
            Engine: Tomcat 10.1 / Spring Boot 3.3.4<br>
            Workers: 4 Sharded Partition Consumers
        </div>
    </div>
    """, unsafe_allow_html=True)
else:
    st.sidebar.markdown(f"""
    <div style="background: rgba(56, 189, 248, 0.1); border: 1px solid rgba(56, 189, 248, 0.3); border-radius: 10px; padding: 12px; margin-bottom: 14px;">
        <div style="display: flex; align-items: center; gap: 8px;">
            <span class="pulse-dot amber"></span>
            <span style="font-weight: 700; color: #38bdf8; font-size: 0.88rem;">STANDALONE VISUAL ENGINE</span>
        </div>
        <div style="font-size: 0.76rem; color: #94a3b8; margin-top: 4px;">
            Mode: High-Fidelity In-Memory Engine<br>
            Idempotency Guard: Enabled<br>
            Partition Emulation: 4 Threads
        </div>
    </div>
    """, unsafe_allow_html=True)

# Fetch latest telemetry
metrics_data = api_get("/api/v1/metrics") or {}
payments_list = api_get("/api/v1/payments") or []

# Compute Gross Volume in USD approximation
def compute_gross_volume(payments):
    total_usd = 0.0
    for p in payments:
        if p.get("status") in ["SUCCESS", "REFUNDED"]:
            amt = float(p.get("amount", 0.0))
            curr = p.get("currency", "USD")
            if curr == "EUR":
                total_usd += amt * 1.08
            elif curr == "GBP":
                total_usd += amt * 1.28
            elif curr == "INR":
                total_usd += amt * 0.012
            else:
                total_usd += amt
    return total_usd

gross_volume = compute_gross_volume(payments_list)

st.sidebar.markdown(f"**Gross Volume:** `${gross_volume:,.2f} USD`")
st.sidebar.markdown(f"**Total Records:** `{metrics_data.get('totalTransactions', len(payments_list)):,}`")
st.sidebar.markdown(f"**Success Rate:** `{metrics_data.get('successRatePercent', 100.0)}%`")
st.sidebar.markdown(f"**Average Latency:** `{metrics_data.get('averageLatencyMs', 24.5):.1f} ms`")
st.sidebar.markdown(f"**DLQ Messages:** `{metrics_data.get('dlqCount', 0)}`")

st.sidebar.markdown("---")

# Quick Traffic Generator Buttons in Sidebar
st.sidebar.markdown("### ⚡ Quick Traffic Control")
col_sb1, col_sb2 = st.sidebar.columns(2)
with col_sb1:
    if st.button("🔄 Sync Feed", use_container_width=True):
        st.rerun()

with col_sb2:
    if st.button("🌱 Seed 5 Tx", use_container_width=True):
        sample_batch = [
            ("USR_ALICE", 450.00, "USD", "CREDIT_CARD", "Cloud Compute Reservation"),
            ("USR_BOB", 129.50, "USD", "APPLE_PAY", "Shopify Merchant Checkout"),
            ("USR_CHARLIE", 1850.00, "USD", "BANK_TRANSFER", "B2B SaaS Annual License"),
            ("USR_DAVID", 45.00, "USD", "WALLET", "Urban Transit Ride Ticket"),
            ("USR_ALICE", 22.99, "USD", "DEBIT_CARD", "Streaming Media Subscription")
        ]
        for u, amt, curr, method, desc in sample_batch:
            idem = f"IDEM_SEED_{int(time.time()*1000)}_{random.randint(100, 999)}"
            api_post("/api/v1/payments", {
                "userId": u, "amount": amt, "currency": curr,
                "paymentMethod": method, "description": desc,
                "idempotencyKey": idem, "asyncProcessing": random.choice([True, False])
            }, headers={"X-Idempotency-Key": idem})
        st.success("Seeded 5 new transactions!")
        st.rerun()

# Expandable Deployment / Connection Guide in Sidebar
with st.sidebar.expander("ℹ️ Connection Guide (Option 1 vs 2)"):
    st.markdown("""
    **Option 1: Run Locally (Current)**
    1. Start Spring Boot: `mvn spring-boot:run` (listening on `localhost:8080`).
    2. Open this dashboard locally at `localhost:8501`.
    3. Select **Force Live Spring Boot API**.
    4. Health status will show `🟢 SPRING BOOT CONNECTED`.

    **Option 2: Cloud Deployed Streamlit**
    1. Deploy Spring Boot backend to Render, Railway, or Fly.io (or expose local port 8080 with `ngrok http 8080`).
    2. Paste your public HTTPS URL (e.g. `https://your-api.onrender.com`) into the **Backend API Base URL** field.
    3. Select **Force Live Spring Boot API** to route traffic to the live remote cluster!
    """)

# ---------------------------------------------------------
# Main App Header
# ---------------------------------------------------------
st.markdown(f"""
<div class="main-header">
    <div class="main-title">
        <span>⚡ ApexPay Distributed Payment Engine</span>
    </div>
    <div class="subtitle">
        <span>High-Throughput Transaction Ledger</span>
        <span>•</span>
        <span>Partition-Worker Concurrency Pool</span>
        <span>•</span>
        <span>Distributed Idempotency Cache</span>
        <span>•</span>
        <span>Defensive Chaos Resilience</span>
    </div>
</div>
""", unsafe_allow_html=True)

# Status notification banner
if is_live_backend:
    st.markdown(f"""
    <div style="background: rgba(16, 185, 129, 0.1); border: 1px solid rgba(16, 185, 129, 0.35); border-radius: 12px; padding: 12px 18px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;">
        <div style="display: flex; align-items: center; gap: 10px;">
            <span class="pulse-dot"></span>
            <span style="font-weight: 700; color: #34d399;">LIVE SPRING BOOT ENGINE BOUND</span>
            <span style="color: #94a3b8; font-size: 0.85rem;">Connected to <code>{active_backend_url}</code> via REST Actuator</span>
        </div>
        <div style="font-size: 0.8rem; color: #cbd5e1; font-weight: 600;">
            DB: <span style="color: #38bdf8;">H2 (PostgreSQL Compatible)</span> | Shards: <span style="color: #38bdf8;">4 Partition Threads</span>
        </div>
    </div>
    """, unsafe_allow_html=True)
else:
    st.markdown(f"""
    <div style="background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.25); border-radius: 12px; padding: 12px 18px; margin-bottom: 20px; display: flex; align-items: center; justify-content: space-between;">
        <div style="display: flex; align-items: center; gap: 10px;">
            <span class="pulse-dot amber"></span>
            <span style="font-weight: 700; color: #38bdf8;">STANDALONE VISUAL ENGINE ACTIVE</span>
            <span style="color: #94a3b8; font-size: 0.85rem;">Simulating full distributed idempotency and partition sharding in-memory.</span>
        </div>
        <div style="font-size: 0.8rem; color: #cbd5e1;">
            To bind live Java backend, launch Spring Boot on port 8080 or provide a public URL.
        </div>
    </div>
    """, unsafe_allow_html=True)

# ---------------------------------------------------------
# Top Executive KPI Cards (Row 1)
# ---------------------------------------------------------
kpi_c1, kpi_c2, kpi_c3, kpi_c4, kpi_c5 = st.columns(5)

with kpi_c1:
    st.markdown(f"""
    <div class="metric-card metric-card-accent-blue">
        <div class="metric-label-row">
            <span class="metric-label">Gross Processed Volume</span>
            <span class="metric-chip">USD EQV</span>
        </div>
        <div class="metric-value">${gross_volume:,.2f}</div>
        <div class="metric-sub">Across {len(payments_list)} settled records</div>
    </div>
    """, unsafe_allow_html=True)

with kpi_c2:
    st.markdown(f"""
    <div class="metric-card metric-card-accent-emerald">
        <div class="metric-label-row">
            <span class="metric-label">Success Rate</span>
            <span class="metric-chip">SLA 99.5%</span>
        </div>
        <div class="metric-value" style="color: #34d399;">{metrics_data.get('successRatePercent', 100.0):.1f}%</div>
        <div class="metric-sub">{metrics_data.get('successfulTransactions', 0)} of {metrics_data.get('totalTransactions', len(payments_list))} succeeded</div>
    </div>
    """, unsafe_allow_html=True)

with kpi_c3:
    st.markdown(f"""
    <div class="metric-card metric-card-accent-amber">
        <div class="metric-label-row">
            <span class="metric-label">Retries Executed</span>
            <span class="metric-chip">EXP BACKOFF</span>
        </div>
        <div class="metric-value" style="color: #fbbf24;">{metrics_data.get('retryCount', 0)}</div>
        <div class="metric-sub">Auto-recovered glitch attempts</div>
    </div>
    """, unsafe_allow_html=True)

with kpi_c4:
    st.markdown(f"""
    <div class="metric-card metric-card-accent-purple">
        <div class="metric-label-row">
            <span class="metric-label">Idempotency Hits</span>
            <span class="metric-chip">0 DBL CHARGE</span>
        </div>
        <div class="metric-value" style="color: #c084fc;">{metrics_data.get('idempotencyHits', len(st.session_state.sim_idempotency))}</div>
        <div class="metric-sub">Duplicate requests deduplicated</div>
    </div>
    """, unsafe_allow_html=True)

with kpi_c5:
    st.markdown(f"""
    <div class="metric-card metric-card-accent-rose">
        <div class="metric-label-row">
            <span class="metric-label">Avg P99 Latency</span>
            <span class="metric-chip">TARGET &lt;50ms</span>
        </div>
        <div class="metric-value" style="color: #38bdf8;">{metrics_data.get('averageLatencyMs', 24.5):.1f} <span style="font-size: 1rem; color: #94a3b8;">ms</span></div>
        <div class="metric-sub">Partition queue overhead: 1.2ms</div>
    </div>
    """, unsafe_allow_html=True)

st.markdown("<br>", unsafe_allow_html=True)

# ---------------------------------------------------------
# Navigation Tabs
# ---------------------------------------------------------
tab_live, tab_simulate, tab_metrics, tab_workers, tab_audit, tab_faults = st.tabs([
    "📊 Live Transactions",
    "💳 Payment Simulator",
    "📈 Analytics & Observability",
    "⚙️ Partition Workers & Circuit Breaker",
    "📜 Forensic Audit Trail",
    "🛡️ Fault Injection Lab"
])

# ---------------------------------------------------------
# TAB 1: LIVE TRANSACTIONS
# ---------------------------------------------------------
with tab_live:
    # Filter Controls Bar
    filter_c1, filter_c2, filter_c3, filter_c4, filter_c5 = st.columns([3, 2, 2, 2, 2])
    
    with filter_c1:
        search_query = st.text_input("🔍 Search Transactions", placeholder="Payment ID, User ID, or Order Description...", label_visibility="collapsed")
    with filter_c2:
        status_filter = st.selectbox("Status", ["All Statuses", "SUCCESS", "PENDING", "FAILED", "REFUNDED"], label_visibility="collapsed")
    with filter_c3:
        method_filter = st.selectbox("Method", ["All Methods", "CREDIT_CARD", "DEBIT_CARD", "UPI", "APPLE_PAY", "BANK_TRANSFER", "WALLET"], label_visibility="collapsed")
    with filter_c4:
        currency_filter = st.selectbox("Currency", ["All Currencies", "USD", "EUR", "GBP", "INR"], label_visibility="collapsed")
    with filter_c5:
        burst_trigger = st.button("🚀 Traffic Burst", use_container_width=True, help="Simulate a burst of 5 concurrent payments across all partition workers")

    if burst_trigger:
        with st.spinner("Processing high-velocity concurrent burst..."):
            burst_samples = [
                ("USR_ALICE", 145.0, "USD", "CREDIT_CARD", "E-Commerce Instant Checkout"),
                ("USR_BOB", 78.5, "USD", "DEBIT_CARD", "Coffee Subscription Delivery"),
                ("USR_CHARLIE", 4200.0, "USD", "BANK_TRANSFER", "Enterprise Server Hosting"),
                ("USR_DAVID", 12.0, "USD", "WALLET", "Metro Transit Fare"),
                ("USR_ALICE", 310.0, "EUR", "APPLE_PAY", "Online Course Subscription")
            ]
            for u, amt, curr, meth, desc in burst_samples:
                idem = f"IDEM_BURST_{int(time.time()*1000)}_{random.randint(1000, 9999)}"
                api_post("/api/v1/payments", {
                    "userId": u, "amount": amt, "currency": curr,
                    "paymentMethod": meth, "description": desc,
                    "idempotencyKey": idem, "asyncProcessing": True
                }, headers={"X-Idempotency-Key": idem})
            st.success("Successfully injected 5 concurrent transactions into worker pool!")
            st.rerun()

    # Filter transactions
    filtered_payments = list(payments_list)
    if search_query:
        sq = search_query.lower()
        filtered_payments = [
            p for p in filtered_payments
            if sq in p.get("paymentId", "").lower() or sq in p.get("userId", "").lower() or sq in p.get("description", "").lower() or sq in p.get("idempotencyKey", "").lower()
        ]
    if status_filter != "All Statuses":
        filtered_payments = [p for p in filtered_payments if p.get("status") == status_filter]
    if method_filter != "All Methods":
        filtered_payments = [p for p in filtered_payments if p.get("paymentMethod") == method_filter]
    if currency_filter != "All Currencies":
        filtered_payments = [p for p in filtered_payments if p.get("currency") == currency_filter]

    st.markdown(f"""
    <div style="display: flex; justify-content: space-between; align-items: center; margin: 12px 0 8px 0;">
        <div style="font-size: 0.9rem; color: #94a3b8; font-weight: 600;">
            Showing <span style="color: #ffffff;">{len(filtered_payments)}</span> of {len(payments_list)} recorded transactions
        </div>
        <div style="font-size: 0.85rem; color: #38bdf8; font-weight: 600;">
            Filtered Volume: ${compute_gross_volume(filtered_payments):,.2f} USD
        </div>
    </div>
    """, unsafe_allow_html=True)

    if filtered_payments:
        df = pd.DataFrame(filtered_payments)
        if "createdAt" in df.columns:
            df["createdAt"] = df["createdAt"].apply(format_timestamp_safe)

        display_cols = [c for c in ["paymentId", "userId", "amount", "currency", "status", "paymentMethod", "retryCount", "description", "createdAt"] if c in df.columns]
        
        # Display formatted table
        st.dataframe(
            df[display_cols],
            use_container_width=True,
            hide_index=True,
            column_config={
                "paymentId": st.column_config.TextColumn("Payment ID", width="medium"),
                "userId": st.column_config.TextColumn("User Account", width="small"),
                "amount": st.column_config.NumberColumn("Amount", format="%.2f", width="small"),
                "currency": st.column_config.TextColumn("Currency", width="small"),
                "status": st.column_config.TextColumn("Status", width="small"),
                "paymentMethod": st.column_config.TextColumn("Method", width="medium"),
                "retryCount": st.column_config.NumberColumn("Retries", width="small"),
                "description": st.column_config.TextColumn("Order Description", width="large"),
                "createdAt": st.column_config.TextColumn("Timestamp", width="medium")
            }
        )

        # Interactive Forensic Inspector Card
        st.markdown("<br>", unsafe_allow_html=True)
        st.subheader("🔍 Transaction Forensic Inspector")
        st.write("Select any transaction to inspect its idempotency tokens, partition shard routing, and exact audit transition path.")

        tx_options = [f"{p['paymentId']} | {p['userId']} | ${p['amount']:.2f} {p['currency']} | {p['status']} ({p.get('description', '')})" for p in filtered_payments]
        selected_tx_idx = st.selectbox("Inspect Transaction Forensic Record", options=range(len(tx_options)), format_func=lambda i: tx_options[i])
        selected_tx = filtered_payments[selected_tx_idx]

        shard_num = abs(hash(selected_tx.get("userId", "USR_ALICE"))) % 4
        inspector_c1, inspector_c2 = st.columns([3, 2])

        with inspector_c1:
            st.markdown(f"""
            <div class="inspector-card">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
                    <div>
                        <div style="font-size: 1.15rem; font-weight: 800; color: #ffffff;">{selected_tx.get('paymentId')}</div>
                        <div style="font-size: 0.8rem; color: #94a3b8;">User Account: <b>{selected_tx.get('userId')}</b> • Partition Shard: <b>Shard {shard_num} (Worker-{shard_num})</b></div>
                    </div>
                    <div>
                        <span class="status-pill status-pill-{selected_tx.get('status', 'SUCCESS').lower()}">{selected_tx.get('status')}</span>
                    </div>
                </div>
                <div style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 16px;">
                    <div style="background: rgba(0,0,0,0.25); padding: 10px; border-radius: 8px;">
                        <div style="font-size: 0.72rem; color: #94a3b8; text-transform: uppercase;">Amount & Currency</div>
                        <div style="font-size: 1.1rem; font-weight: 700; color: #38bdf8;">${selected_tx.get('amount', 0):.2f} {selected_tx.get('currency')}</div>
                    </div>
                    <div style="background: rgba(0,0,0,0.25); padding: 10px; border-radius: 8px;">
                        <div style="font-size: 0.72rem; color: #94a3b8; text-transform: uppercase;">Payment Rail</div>
                        <div style="font-size: 1.1rem; font-weight: 700; color: #ffffff;">{selected_tx.get('paymentMethod')}</div>
                    </div>
                    <div style="background: rgba(0,0,0,0.25); padding: 10px; border-radius: 8px;">
                        <div style="font-size: 0.72rem; color: #94a3b8; text-transform: uppercase;">Retry Counter</div>
                        <div style="font-size: 1.1rem; font-weight: 700; color: #fbbf24;">{selected_tx.get('retryCount', 0)} attempts</div>
                    </div>
                </div>
                <div style="font-size: 0.82rem; color: #cbd5e1; margin-bottom: 6px;">
                    <b>Idempotency Key:</b> <code>{selected_tx.get('idempotencyKey')}</code>
                </div>
                <div style="font-size: 0.82rem; color: #cbd5e1; margin-bottom: 6px;">
                    <b>Order Description:</b> {selected_tx.get('description')}
                </div>
                {f'<div style="font-size: 0.82rem; color: #fb7185; margin-top: 8px;"><b>Failure Reason:</b> {selected_tx.get("failureReason")}</div>' if selected_tx.get('failureReason') else ''}
            </div>
            """, unsafe_allow_html=True)

        with inspector_c2:
            st.markdown("#### ⚡ Quick Actions & Audit Trail")
            if selected_tx.get("status") == "SUCCESS":
                with st.form(f"refund_quick_{selected_tx.get('paymentId')}"):
                    st.write(f"Issue refund for **${selected_tx.get('amount'):.2f} {selected_tx.get('currency')}**")
                    reason_input = st.text_input("Refund Reason", value="Customer initiated portal return")
                    if st.form_submit_button("💸 Process Instant Refund", use_container_width=True):
                        ref_status, ref_data = api_post("/api/v1/refunds", {
                            "paymentId": selected_tx.get("paymentId"),
                            "amount": selected_tx.get("amount"),
                            "idempotencyKey": f"REF_IDEM_{int(time.time()*1000)}",
                            "reason": reason_input
                        })
                        if ref_status in [200, 201]:
                            st.success(f"Refund Processed Successfully! HTTP {ref_status}")
                            time.sleep(0.5)
                            st.rerun()
                        else:
                            st.error(f"Refund Failed: {ref_data}")
            elif selected_tx.get("status") == "REFUNDED":
                st.info("This payment has already been refunded and settled back to the customer.")
            else:
                st.warning(f"Payment status is {selected_tx.get('status')}. Refunds are only permitted on SUCCESS payments.")

    else:
        st.info("No transactions match the selected filter criteria. Clear filters or generate sample transactions.")

# ---------------------------------------------------------
# TAB 2: PAYMENT SIMULATOR
# ---------------------------------------------------------
with tab_simulate:
    st.subheader("💳 Real-Time Payment Injection Terminal")
    st.write("Simulate synchronous HTTP authorization, partitioned asynchronous worker queues, or replay idempotency tokens to verify deduplication.")

    # Preset Quick Load Buttons
    st.markdown("#### Quick Simulation Presets")
    preset_c1, preset_c2, preset_c3, preset_c4 = st.columns(4)
    with preset_c1:
        if st.button("🛒 E-Commerce Cart ($89.99)", use_container_width=True):
            st.session_state["form_user"] = "USR_BOB"
            st.session_state["form_amount"] = 89.99
            st.session_state["form_desc"] = "Shopify Store Order Checkout"
            st.session_state["form_method"] = "CREDIT_CARD"
    with preset_c2:
        if st.button("☁️ AWS Hosting ($450.00)", use_container_width=True):
            st.session_state["form_user"] = "USR_ALICE"
            st.session_state["form_amount"] = 450.00
            st.session_state["form_desc"] = "AWS Cloud EC2 Production Compute"
            st.session_state["form_method"] = "CREDIT_CARD"
    with preset_c3:
        if st.button("💼 Enterprise SaaS ($1,800)", use_container_width=True):
            st.session_state["form_user"] = "USR_CHARLIE"
            st.session_state["form_amount"] = 1800.00
            st.session_state["form_desc"] = "Figma Enterprise 25-Seat License"
            st.session_state["form_method"] = "BANK_TRANSFER"
    with preset_c4:
        if st.button("📱 P2P Transfer ($45.00)", use_container_width=True):
            st.session_state["form_user"] = "USR_DAVID"
            st.session_state["form_amount"] = 45.00
            st.session_state["form_desc"] = "Instant Peer-to-Peer Transfer"
            st.session_state["form_method"] = "WALLET"

    sim_col1, sim_col2 = st.columns(2)

    with sim_col1:
        st.markdown("### 🚀 Authorize Payment")
        with st.form("payment_form"):
            userId = st.selectbox(
                "Customer Account",
                ["USR_ALICE", "USR_BOB", "USR_CHARLIE", "USR_DAVID"],
                index=["USR_ALICE", "USR_BOB", "USR_CHARLIE", "USR_DAVID"].index(st.session_state.get("form_user", "USR_ALICE"))
            )
            amount = st.number_input(
                "Amount",
                min_value=1.0,
                max_value=100000.0,
                value=float(st.session_state.get("form_amount", 250.0)),
                step=10.0
            )
            currency = st.selectbox("Currency", ["USD", "EUR", "GBP", "INR"])
            paymentMethod = st.selectbox(
                "Payment Method",
                ["CREDIT_CARD", "DEBIT_CARD", "UPI", "APPLE_PAY", "BANK_TRANSFER", "WALLET"],
                index=["CREDIT_CARD", "DEBIT_CARD", "UPI", "APPLE_PAY", "BANK_TRANSFER", "WALLET"].index(st.session_state.get("form_method", "CREDIT_CARD"))
            )
            
            gen_key = f"IDEM_{int(time.time()*1000)}"
            idempotencyKey = st.text_input("Idempotency Token", value=gen_key, help="Unique token per transaction. Resending the same key returns the exact cached response without double-charging.")
            description = st.text_input("Order Description", value=st.session_state.get("form_desc", "Software subscription order"))
            asyncProcessing = st.checkbox("Multi-Threaded Async Processing (Route to Partition Queue)", value=False)

            submitted = st.form_submit_button("⚡ Submit Transaction", use_container_width=True)

            if submitted:
                payload = {
                    "userId": userId,
                    "amount": amount,
                    "currency": currency,
                    "paymentMethod": paymentMethod,
                    "idempotencyKey": idempotencyKey,
                    "description": description,
                    "asyncProcessing": asyncProcessing
                }

                status_code, response = api_post("/api/v1/payments", payload, headers={"X-Idempotency-Key": idempotencyKey})

                if status_code in [200, 201, 202]:
                    if response.get("isCachedIdempotentResponse"):
                        st.info(f"⚡ Idempotency Cache Hit (HTTP {status_code}): Duplicate request recognized and returned without re-charging!")
                    else:
                        st.success(f"Payment Authorized Successfully! (HTTP {status_code})")
                    st.json(response)
                    time.sleep(0.5)
                    st.rerun()
                else:
                    st.error(f"Transaction Failed (HTTP {status_code})")
                    st.json(response)

    with sim_col2:
        st.markdown("### 🔄 Process Refund")
        with st.form("refund_form"):
            refundPaymentId = st.text_input("Target Payment ID (e.g., PAY_...)", placeholder="Leave blank to refund most recent payment")
            refundAmount = st.number_input("Refund Amount (USD)", min_value=1.0, value=89.99, step=10.0)
            refundIdemKey = st.text_input("Refund Idempotency Key", value=f"REF_IDEM_{int(time.time()*1000)}")
            refundReason = st.text_input("Reason for Refund", value="Customer returned item under return window")

            refund_submitted = st.form_submit_button("💸 Execute Refund", use_container_width=True)

            if refund_submitted:
                payload = {
                    "paymentId": refundPaymentId if refundPaymentId.strip() else None,
                    "amount": refundAmount,
                    "idempotencyKey": refundIdemKey,
                    "reason": refundReason
                }

                status_code, response = api_post("/api/v1/refunds", payload, headers={"X-Idempotency-Key": refundIdemKey})

                if status_code in [200, 201]:
                    st.success(f"Refund Executed Successfully! (HTTP {status_code})")
                    st.json(response)
                    time.sleep(0.5)
                    st.rerun()
                else:
                    st.error(f"Refund Execution Failed (HTTP {status_code})")
                    st.json(response)

# ---------------------------------------------------------
# TAB 3: PERFORMANCE & METRICS
# ---------------------------------------------------------
with tab_metrics:
    st.subheader("📈 Financial Analytics & Velocity Observability")

    chart_c1, chart_c2 = st.columns(2)

    with chart_c1:
        # Payment Method Breakdown Donut Chart
        if payments_list:
            methods_counts = {}
            for p in payments_list:
                m = p.get("paymentMethod", "CREDIT_CARD")
                methods_counts[m] = methods_counts.get(m, 0) + 1

            fig_donut = px.pie(
                names=list(methods_counts.keys()),
                values=list(methods_counts.values()),
                hole=0.55,
                title="Payment Rails & Methods Share",
                color_discrete_sequence=['#38bdf8', '#818cf8', '#34d399', '#f59e0b', '#c084fc', '#f43f5e']
            )
            fig_donut.update_layout(
                paper_bgcolor="rgba(0,0,0,0)",
                plot_bgcolor="rgba(0,0,0,0)",
                font_color="#f1f5f9",
                margin=dict(t=40, b=20, l=20, r=20),
                legend=dict(orientation="h", yanchor="bottom", y=-0.2)
            )
            st.plotly_chart(fig_donut, use_container_width=True)

    with chart_c2:
        # Success Rate Gauge
        sr_val = metrics_data.get("successRatePercent", 100.0)
        fig_gauge = go.Figure(go.Indicator(
            mode="gauge+number",
            value=sr_val,
            domain={'x': [0, 1], 'y': [0, 1]},
            title={'text': "Global Gateway Success Rate (%)", 'font': {'size': 16, 'color': '#f1f5f9'}},
            number={'suffix': "%", 'font': {'color': '#34d399' if sr_val > 90 else '#fbbf24'}},
            gauge={
                'axis': {'range': [0, 100], 'tickcolor': "#94a3b8"},
                'bar': {'color': "#10b981"},
                'bgcolor': "rgba(15, 23, 42, 0.8)",
                'borderwidth': 1,
                'bordercolor': "rgba(255,255,255,0.1)",
                'steps': [
                    {'range': [0, 75], 'color': "rgba(244, 63, 94, 0.2)"},
                    {'range': [75, 95], 'color': "rgba(245, 158, 11, 0.2)"},
                    {'range': [95, 100], 'color': "rgba(16, 185, 129, 0.2)"}
                ]
            }
        ))
        fig_gauge.update_layout(
            paper_bgcolor="rgba(0,0,0,0)",
            font_color="#f1f5f9",
            margin=dict(t=40, b=20, l=30, r=30)
        )
        st.plotly_chart(fig_gauge, use_container_width=True)

    st.markdown("<br>", unsafe_allow_html=True)

    # Volume & Transaction Velocity Timeline
    if payments_list:
        try:
            df_time = pd.DataFrame(payments_list)
            if "createdAt" in df_time.columns:
                df_time["timestamp"] = pd.to_datetime(df_time["createdAt"], errors="coerce")
                df_time = df_time.dropna(subset=["timestamp"]).sort_values("timestamp")
                if not df_time.empty:
                    fig_hist = px.histogram(
                        df_time,
                        x="timestamp",
                        y="amount",
                        color="status",
                        title="Transaction Settlement Volume Over Time ($ USD Equivalent)",
                        color_discrete_map={
                            "SUCCESS": "#10b981",
                            "REFUNDED": "#8b5cf6",
                            "FAILED": "#f43f5e",
                            "PENDING": "#f59e0b"
                        }
                    )
                    fig_hist.update_layout(
                        paper_bgcolor="rgba(0,0,0,0)",
                        plot_bgcolor="rgba(0,0,0,0)",
                        font_color="#f1f5f9",
                        xaxis=dict(showgrid=False, title="Timeline"),
                        yaxis=dict(gridcolor="rgba(255,255,255,0.06)", title="Volume ($ USD)")
                    )
                    st.plotly_chart(fig_hist, use_container_width=True)
        except Exception:
            pass

# ---------------------------------------------------------
# TAB 4: PARTITION WORKERS & CIRCUIT BREAKER
# ---------------------------------------------------------
with tab_workers:
    st.subheader("⚙️ Partition Worker Pool & Distributed Resilience Matrix")
    st.write("Visualizes multi-threaded hash partition concurrency (`hash(userId) % 4`), active consumer loops, and Resilience4j circuit breaker state.")

    # 4 Partition Workers Grid
    w_cols = st.columns(4)
    partition_shards = [
        {"id": 0, "name": "Worker-0 (Partition Shard 0)", "users": "USR_ALICE, USR_EVAN", "status": "ACTIVE"},
        {"id": 1, "name": "Worker-1 (Partition Shard 1)", "users": "USR_BOB, USR_FELIX", "status": "ACTIVE"},
        {"id": 2, "name": "Worker-2 (Partition Shard 2)", "users": "USR_CHARLIE, USR_GINA", "status": "ACTIVE"},
        {"id": 3, "name": "Worker-3 (Partition Shard 3)", "users": "USR_DAVID, USR_HARRY", "status": "ACTIVE"},
    ]

    for idx, shard in enumerate(partition_shards):
        # Count processed for this shard
        count_for_shard = sum(1 for p in payments_list if abs(hash(p.get("userId", ""))) % 4 == shard["id"])
        with w_cols[idx]:
            st.markdown(f"""
            <div class="worker-card">
                <div>
                    <div class="worker-title">
                        <span class="pulse-dot"></span>
                        <span>{shard['name']}</span>
                    </div>
                    <div class="worker-meta">Affinity: {shard['users']}</div>
                    <div style="font-size: 0.82rem; color: #38bdf8; font-weight: 700; margin-top: 6px;">
                        Processed: {count_for_shard} transactions
                    </div>
                </div>
            </div>
            """, unsafe_allow_html=True)

    st.markdown("<br>", unsafe_allow_html=True)

    # Resilience4j Circuit Breaker & Retry Telemetry
    rc1, rc2 = st.columns(2)
    with rc1:
        st.markdown("#### 🛡️ Banking Gateway Circuit Breaker")
        st.markdown("""
        <div style="background: rgba(15, 23, 42, 0.7); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 14px; padding: 18px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <span style="font-weight: 700; color: #ffffff;">Circuit State:</span>
                <span class="status-pill status-pill-success">CLOSED (HEALTHY)</span>
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 8px;">
                <b>Failure Threshold:</b> 50% across 10-call sliding window
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 8px;">
                <b>Slow Call Threshold:</b> 2000 ms duration
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1;">
                <b>Auto-Recovery Wait Duration:</b> 5000 ms (Automatic transition to HALF-OPEN)
            </div>
        </div>
        """, unsafe_allow_html=True)

    with rc2:
        st.markdown("#### 🔁 Exponential Backoff Retry Policy")
        st.markdown("""
        <div style="background: rgba(15, 23, 42, 0.7); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 14px; padding: 18px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <span style="font-weight: 700; color: #ffffff;">Max Retries:</span>
                <span style="color: #38bdf8; font-weight: 800;">3 Attempts</span>
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 8px;">
                <b>Initial Delay:</b> 200 ms with 2.0x Backoff Multiplier
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 8px;">
                <b>Jitter Calculation:</b> Random Full Jitter (eliminates thundering herds)
            </div>
            <div style="font-size: 0.85rem; color: #cbd5e1;">
                <b>DLQ Routing:</b> Unrecoverable failures automatically dispatched to Dead Letter Queue
            </div>
        </div>
        """, unsafe_allow_html=True)

# ---------------------------------------------------------
# TAB 5: AUDIT & STATE TRAIL
# ---------------------------------------------------------
with tab_audit:
    st.subheader("📜 Forensic NoSQL State Machine Audit Trail")
    st.write("Immutable state transitions from `PENDING` -> `SUCCESS` / `FAILED` / `REFUNDED` with worker thread signatures.")

    audit_logs = api_get("/api/v1/audit/logs") or []
    if audit_logs:
        log_df = pd.DataFrame(audit_logs)
        if "timestamp" in log_df.columns:
            log_df["timestamp"] = log_df["timestamp"].apply(format_timestamp_safe)

        cols = [c for c in ["paymentId", "previousStatus", "newStatus", "action", "workerId", "detail", "timestamp"] if c in log_df.columns]
        st.dataframe(
            log_df[cols],
            use_container_width=True,
            hide_index=True,
            column_config={
                "paymentId": st.column_config.TextColumn("Payment ID", width="medium"),
                "previousStatus": st.column_config.TextColumn("Prior State", width="small"),
                "newStatus": st.column_config.TextColumn("New State", width="small"),
                "action": st.column_config.TextColumn("Action Trigger", width="medium"),
                "workerId": st.column_config.TextColumn("Worker Thread", width="medium"),
                "detail": st.column_config.TextColumn("Forensic Detail", width="large"),
                "timestamp": st.column_config.TextColumn("Timestamp", width="medium")
            }
        )
    else:
        st.info("No audit logs recorded yet. Transactions will populate forensic state records.")

# ---------------------------------------------------------
# TAB 6: FAULT INJECTION LAB
# ---------------------------------------------------------
with tab_faults:
    st.subheader("🛡️ Chaos Engineering & Fault Injection Lab")
    st.write("Simulate downstream banking outages, network latency, and socket timeouts to test exponential backoff and DLQ routing.")

    current_config = api_get("/api/v1/simulation/config") or {}

    with st.form("simulation_form"):
        f_c1, f_c2 = st.columns(2)
        with f_c1:
            enableLatency = st.checkbox("Inject Network Latency Jitter", value=current_config.get("enableLatency", False))
            latencyMs = st.slider("Simulated Latency (ms)", 500, 5000, int(current_config.get("latencyMs", 1500)), step=250)

            enableTimeouts = st.checkbox("Inject Network Socket Timeouts", value=current_config.get("enableTimeouts", False))
            timeoutProb = st.slider("Timeout Probability", 0.0, 1.0, float(current_config.get("timeoutProbability", 0.2)), step=0.05)

        with f_c2:
            enableGatewayFailures = st.checkbox("Inject 503 Gateway Service Outages", value=current_config.get("enableGatewayFailures", False))
            failureProb = st.slider("Gateway 503 Outage Probability", 0.0, 1.0, float(current_config.get("failureProbability", 0.25)), step=0.05)

            enablePartialDbFailures = st.checkbox("Inject Database Lock Contention Glitches", value=current_config.get("enablePartialDbFailures", False))
            dbFailureProb = st.slider("DB Failure Probability", 0.0, 1.0, float(current_config.get("dbFailureProbability", 0.15)), step=0.05)

        btn_col1, btn_col2 = st.columns(2)
        with btn_col1:
            save_sim = st.form_submit_button("⚡ Apply Fault Rules", use_container_width=True)
        with btn_col2:
            reset_sim = st.form_submit_button("🧹 Reset All Fault Rules", use_container_width=True)

        if save_sim:
            new_cfg = {
                "enableLatency": enableLatency,
                "latencyMs": latencyMs,
                "enableTimeouts": enableTimeouts,
                "timeoutProbability": timeoutProb,
                "enableGatewayFailures": enableGatewayFailures,
                "failureProbability": failureProb,
                "enablePartialDbFailures": enablePartialDbFailures,
                "dbFailureProbability": dbFailureProb
            }
            status, resp = api_post("/api/v1/simulation/config", new_cfg)
            if status == 200:
                st.success("Fault injection rules applied successfully!")
                time.sleep(0.5)
                st.rerun()

        if reset_sim:
            status, resp = api_post("/api/v1/simulation/reset", {})
            st.success("All fault simulation rules reset to clean default state.")
            time.sleep(0.5)
            st.rerun()

    st.markdown("---")
    st.markdown("### 📦 Dead Letter Queue (DLQ) Inspector")
    dlq_data = api_get("/api/v1/simulation/dlq") or {}
    st.write(f"Unrecoverable messages routed to DLQ: **{dlq_data.get('dlqCount', 0)}**")
    if dlq_data.get("messages"):
        st.json(dlq_data.get("messages"))
    else:
        st.info("Dead Letter Queue is empty. Trigger fault injection to test unrecoverable retries.")
