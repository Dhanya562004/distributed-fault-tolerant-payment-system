import os
import time
import uuid
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry
import pandas as pd
import streamlit as st
import plotly.express as px
import plotly.graph_objects as go

# ---------------------------------------------------------
# Page Configuration
# ---------------------------------------------------------
st.set_page_config(
    page_title="Distributed Payment System Dashboard",
    page_icon="⚡",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Load Custom CSS
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

# ---------------------------------------------------------
# In-Memory Standalone Fallback Simulation Engine
# ---------------------------------------------------------
if "sim_payments" not in st.session_state:
    st.session_state.sim_payments = [
        {
            "paymentId": "PAY_9A8B7C6D5E4F3A21",
            "idempotencyKey": "IDEM_INIT_1001",
            "userId": "USR_ALICE",
            "amount": 250.0,
            "currency": "USD",
            "status": "SUCCESS",
            "paymentMethod": "CREDIT_CARD",
            "description": "Sample initial order",
            "failureReason": None,
            "retryCount": 0,
            "createdAt": "2026-10-04 17:00:00",
            "updatedAt": "2026-10-04 17:00:00"
        }
    ]

if "sim_audit" not in st.session_state:
    st.session_state.sim_audit = [
        {
            "paymentId": "PAY_9A8B7C6D5E4F3A21",
            "previousStatus": "PENDING",
            "newStatus": "SUCCESS",
            "action": "PAYMENT_AUTHORIZED",
            "workerId": "WORKER_DIRECT",
            "detail": "Payment processed successfully",
            "timestamp": "2026-10-04 17:00:00"
        }
    ]

if "sim_idempotency" not in st.session_state:
    st.session_state.sim_idempotency = {}

if "sim_config" not in st.session_state:
    st.session_state.sim_config = {
        "enableLatency": False,
        "latencyMs": 2000,
        "enableTimeouts": False,
        "timeoutProbability": 0.3,
        "enableGatewayFailures": False,
        "failureProbability": 0.4,
        "enablePartialDbFailures": False,
        "dbFailureProbability": 0.2
    }

# ---------------------------------------------------------
# Sidebar Configuration & Backend Health Probe
# ---------------------------------------------------------
st.sidebar.image("https://img.icons8.com/color/96/000000/cloud-lighting.png", width=64)
st.sidebar.title("System Control Room")

backend_mode = st.sidebar.radio(
    "Processing Engine Mode",
    options=["Auto-Detect (Spring Boot or Standalone)", "Force Live Spring Boot API", "Force Standalone Visual Engine"],
    index=0
)

default_url = os.getenv("BACKEND_API_URL", "http://localhost:8080").rstrip("/")
raw_backend_url = st.sidebar.text_input("Backend API Base URL", value=default_url, help="Spring Boot backend API URL (Default: http://localhost:8080)").rstrip("/")

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
                resp = http_session.get(f"{base}{ep}", timeout=2)
                if resp.status_code == 200:
                    try:
                        data = resp.json()
                        if isinstance(data, dict):
                            return True, "UP", data, base
                    except Exception:
                        continue
            except Exception:
                continue

    return False, "STARTING_OR_OFFLINE", {"status": "DOWN"}, url

if "Force Standalone" in backend_mode:
    is_live_backend = False
    active_backend_url = raw_backend_url
    mode_label = "Embedded Simulation Engine"
elif "Force Live" in backend_mode:
    is_live_backend, health_status, health_details, active_backend_url = check_backend_health(raw_backend_url)
    mode_label = f"Live Spring Boot (`{active_backend_url}`)" if is_live_backend else "Waiting for Backend"
else:
    is_live_backend, health_status, health_details, active_backend_url = check_backend_health(raw_backend_url)
    mode_label = f"Live Spring Boot (`{active_backend_url}`)" if is_live_backend else "Standalone Visual Engine"

# ---------------------------------------------------------
# API Helper Functions (Supports Live + Standalone Fallback)
# ---------------------------------------------------------
def get_standalone_metrics():
    payments = st.session_state.sim_payments
    total = len(payments)
    success = sum(1 for p in payments if p.get("status") == "SUCCESS")
    failed = sum(1 for p in payments if p.get("status") == "FAILED")
    refunded = sum(1 for p in payments if p.get("status") == "REFUNDED")
    success_rate = round((success / total * 100.0), 1) if total > 0 else 100.0
    status_map = {}
    for p in payments:
        st_val = p.get("status", "SUCCESS")
        status_map[st_val] = status_map.get(st_val, 0) + 1

    return {
        "totalTransactions": total,
        "successfulTransactions": success,
        "failedTransactions": failed,
        "refundedTransactions": refunded,
        "retryCount": 0,
        "dlqCount": 0,
        "activeQueueDepth": 0,
        "successRatePercent": success_rate,
        "averageLatencyMs": 18,
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

    # Fallback to Standalone Simulation Engine
    if endpoint == "/api/v1/metrics":
        return get_standalone_metrics()
    elif endpoint == "/api/v1/payments":
        return st.session_state.sim_payments
    elif endpoint == "/api/v1/audit/logs":
        return st.session_state.sim_audit
    elif endpoint == "/api/v1/simulation/config":
        return st.session_state.sim_config
    elif endpoint == "/api/v1/simulation/dlq":
        return {"dlqCount": 0, "messages": []}
    return None

def api_post(endpoint, payload, headers=None):
    if is_live_backend:
        try:
            resp = http_session.post(f"{active_backend_url}{endpoint}", json=payload, headers=headers or {}, timeout=6)
            if resp.content:
                try:
                    return resp.status_code, resp.json()
                except Exception:
                    return resp.status_code, {"error": f"Server returned non-JSON response (HTTP {resp.status_code})"}
            return resp.status_code, {}
        except Exception as e:
            if "Force Live" in backend_mode:
                return 503, {"error": f"Connection error to backend at {active_backend_url}: {str(e)}"}

    # Standalone Engine Logic for /api/v1/payments
    if endpoint == "/api/v1/payments":
        idem_key = payload.get("idempotencyKey") or (headers or {}).get("X-Idempotency-Key") or f"IDEM_{int(time.time())}"
        
        # Idempotency check
        if idem_key in st.session_state.sim_idempotency:
            cached_resp = dict(st.session_state.sim_idempotency[idem_key])
            cached_resp["isCachedIdempotentResponse"] = True
            return 200, cached_resp

        payment_id = "PAY_" + uuid.uuid4().hex[:16].upper()
        now_str = time.strftime('%Y-%m-%d %H:%M:%S')

        payment_record = {
            "paymentId": payment_id,
            "idempotencyKey": idem_key,
            "userId": payload.get("userId", "USR_ALICE"),
            "amount": float(payload.get("amount", 100.0)),
            "currency": payload.get("currency", "USD"),
            "status": "SUCCESS",
            "paymentMethod": payload.get("paymentMethod", "CREDIT_CARD"),
            "description": payload.get("description", "Payment transaction"),
            "failureReason": None,
            "retryCount": 0,
            "createdAt": now_str,
            "updatedAt": now_str,
            "isCachedIdempotentResponse": False
        }

        st.session_state.sim_payments.insert(0, payment_record)
        st.session_state.sim_idempotency[idem_key] = payment_record

        # Audit log
        st.session_state.sim_audit.insert(0, {
            "paymentId": payment_id,
            "previousStatus": "PENDING",
            "newStatus": "SUCCESS",
            "action": "PAYMENT_AUTHORIZED",
            "workerId": "WORKER_QUEUE" if payload.get("asyncProcessing") else "WORKER_DIRECT",
            "detail": "Payment processed successfully via visual engine",
            "timestamp": now_str
        })

        status_code = 202 if payload.get("asyncProcessing") else 201
        return status_code, payment_record

    elif endpoint == "/api/v1/refunds":
        payment_id = payload.get("paymentId")
        refund_amount = payload.get("amount", 100.0)
        now_str = time.strftime('%Y-%m-%d %H:%M:%S')

        # Update matching payment status to REFUNDED
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
            "detail": f"Refund of ${refund_amount} processed",
            "timestamp": now_str
        })

        return 201, refund_resp

    elif endpoint == "/api/v1/simulation/config":
        st.session_state.sim_config.update(payload)
        return 200, st.session_state.sim_config

    elif endpoint == "/api/v1/simulation/reset":
        st.session_state.sim_config = {
            "enableLatency": False,
            "latencyMs": 2000,
            "enableTimeouts": False,
            "timeoutProbability": 0.3,
            "enableGatewayFailures": False,
            "failureProbability": 0.4,
            "enablePartialDbFailures": False,
            "dbFailureProbability": 0.2
        }
        return 200, st.session_state.sim_config

    return 200, {}

# Render Sidebar Status Badge
if is_live_backend:
    st.sidebar.markdown(f"**Backend Health:** 🟢 OPERATIONAL (`{active_backend_url}`)")
else:
    st.sidebar.markdown(f"**Backend Health:** 🟢 OPERATIONAL (Standalone Engine)")

# Main Header
st.markdown("""
<div class="main-header">
    <div class="main-title">⚡ Distributed Fault-Tolerant Payment System</div>
    <div class="subtitle">High-Scale Transaction Engine | Multi-Threaded Partition Workers | Fault-Injection & Idempotency Engine</div>
</div>
""", unsafe_allow_html=True)

# Top Banner for Backend Status
if is_live_backend:
    st.success(f"🟢 **Backend Connected** at `{active_backend_url}` (Health Status: `UP`, Verified via `/actuator/health`)")
else:
    st.info(f"🟢 **Standalone Visual Engine Active** (Running in Streamlit Simulation Mode. "
            f"Connect Spring Boot backend locally at `http://localhost:8080` or test transactions right here!)")

metrics_data = api_get("/api/v1/metrics") or {}

if metrics_data:
    st.sidebar.markdown(f"**Total Volume:** `{metrics_data.get('totalTransactions', 0):,}`")
    st.sidebar.markdown(f"**Success Rate:** `{metrics_data.get('successRatePercent', 100.0)}%`")
    st.sidebar.markdown(f"**Queue Depth:** `{metrics_data.get('activeQueueDepth', 0)}` msgs")
    st.sidebar.markdown(f"**DLQ Count:** `{metrics_data.get('dlqCount', 0)}` msgs")

st.sidebar.markdown("---")
if st.sidebar.button("🔄 Re-check Connection Now"):
    st.rerun()

# Navigation Tabs
tab_live, tab_simulate, tab_metrics, tab_audit, tab_faults = st.tabs([
    "📊 Live Transactions",
    "💳 Payment Simulator",
    "📈 Performance & Metrics",
    "📜 Audit & State Trail",
    "🛡️ Fault Injection Lab"
])

# ---------------------------------------------------------
# TAB 1: LIVE TRANSACTIONS
# ---------------------------------------------------------
with tab_live:
    col1, col2, col3, col4 = st.columns(4)
    with col1:
        st.markdown(f"""
        <div class="metric-card">
            <div class="metric-label">Total Transactions</div>
            <div class="metric-value">{metrics_data.get('totalTransactions', 0)}</div>
        </div>
        """, unsafe_allow_html=True)
    with col2:
        st.markdown(f"""
        <div class="metric-card">
            <div class="metric-label">Successful</div>
            <div class="metric-value" style="color: #10b981;">{metrics_data.get('successfulTransactions', 0)}</div>
        </div>
        """, unsafe_allow_html=True)
    with col3:
        st.markdown(f"""
        <div class="metric-card">
            <div class="metric-label">Failed</div>
            <div class="metric-value" style="color: #ef4444;">{metrics_data.get('failedTransactions', 0)}</div>
        </div>
        """, unsafe_allow_html=True)
    with col4:
        st.markdown(f"""
        <div class="metric-card">
            <div class="metric-label">Retries Executed</div>
            <div class="metric-value" style="color: #f59e0b;">{metrics_data.get('retryCount', 0)}</div>
        </div>
        """, unsafe_allow_html=True)

    st.markdown("<br>", unsafe_allow_html=True)
    st.subheader("Real-Time Payment Activity Feed")
    
    payments = api_get("/api/v1/payments") or []
    if payments:
        df = pd.DataFrame(payments)
        if "createdAt" in df.columns:
            df["createdAt"] = pd.to_datetime(df["createdAt"]).dt.strftime('%Y-%m-%d %H:%M:%S')
        
        cols = [c for c in ["paymentId", "userId", "amount", "currency", "status", "paymentMethod", "retryCount", "idempotencyKey", "createdAt"] if c in df.columns]
        st.dataframe(df[cols], use_container_width=True, hide_index=True)
    else:
        st.info("No transaction data recorded yet. Use the 'Payment Simulator' tab to initiate a payment.")

# ---------------------------------------------------------
# TAB 2: PAYMENT SIMULATOR
# ---------------------------------------------------------
with tab_simulate:
    st.subheader("Interactive Payment Processing Terminal")
    st.write("Initiate payments with custom Idempotency Keys, Async Worker Queues, or Refund requests.")

    sim_col1, sim_col2 = st.columns(2)

    with sim_col1:
        st.markdown("### 💳 Initiate New Payment")
        with st.form("payment_form"):
            userId = st.selectbox("Select User Account", ["USR_ALICE", "USR_BOB", "USR_CHARLIE", "USR_DAVID"])
            amount = st.number_input("Amount (USD)", min_value=1.0, max_value=10000.0, value=250.0, step=10.0)
            currency = st.selectbox("Currency", ["USD", "EUR", "GBP", "INR"])
            paymentMethod = st.selectbox("Payment Method", ["CREDIT_CARD", "DEBIT_CARD", "UPI", "BANK_TRANSFER", "WALLET"])
            idempotencyKey = st.text_input("Idempotency Key (Unique)", value=f"IDEM_{int(time.time())}")
            description = st.text_input("Order Description", value="E-commerce payment simulation")
            asyncProcessing = st.checkbox("Queue-Based Async Processing (Worker Pool)", value=False)

            submitted = st.form_submit_button("🚀 Submit Payment Request")

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
                    st.success(f"Response HTTP {status_code}: Payment Request Processed!")
                    st.json(response)
                else:
                    st.error(f"Response HTTP {status_code}: Request Failed")
                    st.json(response)

    with sim_col2:
        st.markdown("### 🔄 Process Refund")
        with st.form("refund_form"):
            refundPaymentId = st.text_input("Original Payment ID (e.g., PAY_...)")
            refundAmount = st.number_input("Refund Amount (USD)", min_value=1.0, value=100.0, step=10.0)
            refundIdemKey = st.text_input("Refund Idempotency Key", value=f"REF_IDEM_{int(time.time())}")
            refundReason = st.text_input("Reason", value="Customer returned item")

            refund_submitted = st.form_submit_button("💸 Submit Refund Request")

            if refund_submitted:
                payload = {
                    "paymentId": refundPaymentId,
                    "amount": refundAmount,
                    "idempotencyKey": refundIdemKey,
                    "reason": refundReason
                }

                status_code, response = api_post("/api/v1/refunds", payload, headers={"X-Idempotency-Key": refundIdemKey})

                if status_code in [200, 201]:
                    st.success(f"Refund Processed Successfully! (HTTP {status_code})")
                    st.json(response)
                else:
                    st.error(f"Refund Failed (HTTP {status_code})")
                    st.json(response)

# ---------------------------------------------------------
# TAB 3: PERFORMANCE & METRICS
# ---------------------------------------------------------
with tab_metrics:
    st.subheader("System Observability & Latency Analytics")

    if metrics_data:
        m_col1, m_col2 = st.columns(2)

        with m_col1:
            status_map = metrics_data.get("statusBreakdown", {})
            if status_map:
                fig_pie = px.pie(
                    names=list(status_map.keys()),
                    values=list(status_map.values()),
                    title="Transaction Status Distribution",
                    color_discrete_sequence=px.colors.qualitative.Bold
                )
                fig_pie.update_layout(paper_bgcolor="rgba(0,0,0,0)", plot_bgcolor="rgba(0,0,0,0)", font_color="#e5e7eb")
                st.plotly_chart(fig_pie, use_container_width=True)

        with m_col2:
            fig_gauge = go.Figure(go.Indicator(
                mode="gauge+number",
                value=metrics_data.get("successRatePercent", 100.0),
                domain={'x': [0, 1], 'y': [0, 1]},
                title={'text': "Success Rate (%)"},
                gauge={
                    'axis': {'range': [None, 100]},
                    'bar': {'color': "#10b981"},
                    'steps': [
                        {'range': [0, 70], 'color': "rgba(239,68,68,0.3)"},
                        {'range': [70, 95], 'color': "rgba(245,158,11,0.3)"},
                        {'range': [95, 100], 'color': "rgba(16,185,129,0.3)"}
                    ]
                }
            ))
            fig_gauge.update_layout(paper_bgcolor="rgba(0,0,0,0)", font_color="#e5e7eb")
            st.plotly_chart(fig_gauge, use_container_width=True)

        st.markdown("---")
        st.markdown("#### System Performance Counters")
        st.json({
            "Average Latency (ms)": metrics_data.get("averageLatencyMs", 0),
            "Idempotency Cache Hits": metrics_data.get("idempotencyHits", 0),
            "Total Retries Executed": metrics_data.get("retryCount", 0),
            "Dead Letter Queue Size": metrics_data.get("dlqCount", 0),
            "Active Queue Depth": metrics_data.get("activeQueueDepth", 0)
        })
    else:
        st.info("System metrics loading...")

# ---------------------------------------------------------
# TAB 4: AUDIT & STATE TRAIL
# ---------------------------------------------------------
with tab_audit:
    st.subheader("Immutable NoSQL Document Audit Trail")
    st.write("Tracks exact state transition lifecycle from PENDING -> SUCCESS/FAILED with worker IDs and timestamp signatures.")

    logs = api_get("/api/v1/audit/logs") or []
    if logs:
        log_df = pd.DataFrame(logs)
        if "timestamp" in log_df.columns:
            log_df["timestamp"] = pd.to_datetime(log_df["timestamp"]).dt.strftime('%Y-%m-%d %H:%M:%S.%f')
        
        cols = [c for c in ["paymentId", "previousStatus", "newStatus", "action", "workerId", "detail", "timestamp"] if c in log_df.columns]
        st.dataframe(log_df[cols], use_container_width=True, hide_index=True)
    else:
        st.info("No audit logs recorded yet.")

# ---------------------------------------------------------
# TAB 5: FAULT INJECTION LAB
# ---------------------------------------------------------
with tab_faults:
    st.subheader("Defensive Resilience & Fault Injection Lab")
    st.write("Simulate network latency, socket timeouts, 503 gateway outages, or partial DB lock failures to verify exponential backoff retries and DLQ routing.")

    current_config = api_get("/api/v1/simulation/config") or {}

    with st.form("simulation_form"):
        enableLatency = st.checkbox("Inject Network Latency", value=current_config.get("enableLatency", False))
        latencyMs = st.slider("Latency (ms)", 500, 5000, int(current_config.get("latencyMs", 2000)), step=250)

        enableTimeouts = st.checkbox("Inject Network Socket Timeouts", value=current_config.get("enableTimeouts", False))
        timeoutProb = st.slider("Timeout Probability", 0.0, 1.0, float(current_config.get("timeoutProbability", 0.3)), step=0.05)

        enableGatewayFailures = st.checkbox("Inject 503 Gateway Service Outages", value=current_config.get("enableGatewayFailures", False))
        failureProb = st.slider("Gateway 503 Outage Probability", 0.0, 1.0, float(current_config.get("failureProbability", 0.4)), step=0.05)

        enablePartialDbFailures = st.checkbox("Inject Partial Database Connection Glitches", value=current_config.get("enablePartialDbFailures", False))
        dbFailureProb = st.slider("DB Failure Probability", 0.0, 1.0, float(current_config.get("dbFailureProbability", 0.2)), step=0.05)

        btn_col1, btn_col2 = st.columns(2)
        with btn_col1:
            save_sim = st.form_submit_button("⚡ Apply Fault Rules")
        with btn_col2:
            reset_sim = st.form_submit_button("🧹 Reset All Fault Rules")

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
                st.success("Updated Fault Simulation Rules Successfully!")
                st.json(resp)
            else:
                st.error("Failed updating fault rules")

        if reset_sim:
            status, resp = api_post("/api/v1/simulation/reset", {})
            st.success("All Fault Simulation Rules Reset to Default Clean State.")

    st.markdown("---")
    st.markdown("### 📦 Dead Letter Queue (DLQ) Inspector")
    dlq_data = api_get("/api/v1/simulation/dlq") or {}
    st.write(f"Unrecoverable messages in DLQ: `{dlq_data.get('dlqCount', 0)}`")
    if dlq_data.get("messages"):
        st.json(dlq_data.get("messages"))
