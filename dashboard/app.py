import os
import time
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry
import pandas as pd
import streamlit as st
import plotly.express as px
import plotly.graph_objects as go

# ---------------------------------------------------------
# Page Setup
# ---------------------------------------------------------
st.set_page_config(
    page_title="Distributed Payment Processing System Dashboard",
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
# Robust Session & Retry Configuration
# ---------------------------------------------------------
def create_robust_session():
    session = requests.Session()
    retry_strategy = Retry(
        total=3,
        backoff_factor=0.3,
        status_forcelist=[500, 502, 503, 504],
        raise_on_status=False
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session.mount("http://", adapter)
    session.mount("https://", adapter)
    return session

http_session = create_robust_session()

# ---------------------------------------------------------
# Sidebar Configuration & Backend Connection URL
# ---------------------------------------------------------
st.sidebar.image("https://img.icons8.com/color/96/000000/cloud-lighting.png", width=64)
st.sidebar.title("System Control Room")

default_url = os.getenv("BACKEND_API_URL", "http://localhost:8080").rstrip("/")
backend_url = st.sidebar.text_input("Backend API Base URL", value=default_url).rstrip("/")

# Health Check Helper
def check_backend_health(url):
    try:
        # Check /actuator/health first
        health_resp = http_session.get(f"{url}/actuator/health", timeout=2)
        if health_resp.status_code == 200:
            return True, "UP", health_resp.json()
        
        # Fallback to metrics check
        metrics_resp = http_session.get(f"{url}/api/v1/metrics", timeout=2)
        if metrics_resp.status_code == 200:
            return True, "UP", {"status": "UP"}
    except Exception:
        pass
    return False, "OFFLINE", {"status": "DOWN"}

is_healthy, health_status, health_details = check_backend_health(backend_url)

# API Helper Functions (Non-blocking, Exception-safe)
def api_get(endpoint):
    if not is_healthy:
        return None
    try:
        resp = http_session.get(f"{backend_url}{endpoint}", timeout=4)
        if resp.status_code == 200:
            return resp.json()
    except Exception:
        pass
    return None

def api_post(endpoint, payload, headers=None):
    if not is_healthy:
        return 503, {"error": f"Backend offline at {backend_url}"}
    try:
        resp = http_session.post(f"{backend_url}{endpoint}", json=payload, headers=headers or {}, timeout=6)
        return resp.status_code, resp.json() if resp.content else {}
    except Exception as e:
        return 500, {"error": f"Connection error: {str(e)}"}

# Render Sidebar Health Badge
if is_healthy:
    st.sidebar.markdown("**Backend Health:** 🟢 OPERATIONAL (`/actuator/health`)")
else:
    st.sidebar.markdown("**Backend Health:** 🔴 UNREACHABLE")

# Header
st.markdown("""
<div class="main-header">
    <div class="main-title">⚡ Distributed Fault-Tolerant Payment System</div>
    <div class="subtitle">High-Scale Transaction Processing Engine | Multi-Threaded Partition Workers | Fault-Injection & Idempotency Engine</div>
</div>
""", unsafe_allow_html=True)

# Top Non-Blocking Banner if Backend is Down
if not is_healthy:
    st.warning(f"⚠️ **Backend API server is offline or unreachable at `{backend_url}`**\n\n"
               f"Please ensure your Java Spring Boot application is running (`mvn spring-boot:run` or `docker-compose up`). "
               f"You can update the API URL in the sidebar once active.")

metrics_data = api_get("/api/v1/metrics") or {}

if metrics_data:
    st.sidebar.markdown(f"**Total Volume:** `{metrics_data.get('totalTransactions', 0):,}`")
    st.sidebar.markdown(f"**Success Rate:** `{metrics_data.get('successRatePercent', 100.0)}%`")
    st.sidebar.markdown(f"**Queue Depth:** `{metrics_data.get('activeQueueDepth', 0)}` msgs")
    st.sidebar.markdown(f"**DLQ Count:** `{metrics_data.get('dlqCount', 0)}` msgs")

st.sidebar.markdown("---")
if st.sidebar.button("🔄 Re-check Backend Connection"):
    st.rerun()

# Main Navigation Tabs
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
        if is_healthy:
            st.info("No transaction data recorded yet. Use the 'Payment Simulator' tab to initiate a transaction.")
        else:
            st.info("Waiting for backend server connection to load transaction activity feed.")

# ---------------------------------------------------------
# TAB 2: PAYMENT SIMULATOR
# ---------------------------------------------------------
with tab_simulate:
    st.subheader("Interactive Payment Processing Terminal")
    st.write("Initiate real payments with custom Idempotency Keys, Async Worker Queues, or Refund requests.")

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
        st.info("System performance analytics will display once backend server is active.")

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
