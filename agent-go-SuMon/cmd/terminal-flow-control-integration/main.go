// Package main runs isolated WSL terminal flow-control acceptance scenarios.
package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"agent-go-SuMon/internal/wsclient"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
)

const (
	backendURL       = "http://172.29.240.1:18081"
	monitorURL       = "ws://172.29.240.1:18081/ws/monitor?ticket="
	serverNamePrefix = "terminal-flow-control-"
	agentLogPath     = "/tmp/susumonitor-terminal-flow-control/agent-runtime.log"
	inputBurstFrames = 180
	// Spring CloseStatus.SESSION_NOT_RELIABLE has no equivalent named coder/websocket constant.
	monitorUnreliableCloseCode = 1012
)

// apiResponse is the backend's standard API envelope.
type apiResponse struct {
	Code int             `json:"code"`
	Data json.RawMessage `json:"data"`
}

// monitorTicketData holds a single-use Monitor connection ticket.
type monitorTicketData struct {
	Ticket string `json:"ticket"`
}

// serverData holds the ID returned for an isolated server record.
type serverData struct {
	ID int64 `json:"id"`
}

// agentTokenData holds a temporary Agent credential in process memory.
type agentTokenData struct {
	AgentToken string `json:"agent_token"`
}

// flowRunner owns one isolated server, temporary Agent, and all scenario state.
type flowRunner struct {
	token       string
	serverID    int64
	agentBinary string
	agent       *exec.Cmd
	agentLog    *os.File
}

// main runs one deterministic flow-control scenario. Java output and monitor-backpressure
// scenarios require separate Java processes because their limits are startup-only settings.
func main() {
	username := os.Getenv("SUSUMONITOR_INTEGRATION_USERNAME")
	password := os.Getenv("SUSUMONITOR_INTEGRATION_PASSWORD")
	agentBinary := os.Getenv("SUSUMONITOR_INTEGRATION_AGENT_BINARY")
	if username == "" || password == "" || agentBinary == "" {
		fail("missing short-lived integration session environment")
	}
	defer unset("SUSUMONITOR_INTEGRATION_USERNAME")
	defer unset("SUSUMONITOR_INTEGRATION_PASSWORD")

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()
	token, err := login(ctx, username, password)
	if err != nil {
		fail(err.Error())
	}
	if err := verifyAdmin(ctx, token); err != nil {
		fail(err.Error())
	}
	serverID, err := createServer(ctx, token)
	if err != nil {
		fail(err.Error())
	}
	agentToken, err := registerAgent(ctx, token, serverID)
	if err != nil {
		fail(err.Error())
	}
	runner := &flowRunner{token: token, serverID: serverID, agentBinary: agentBinary}
	defer revokeAgent(context.Background(), token, serverID)
	defer runner.stopAgent()

	scenario := getenv("SUSUMONITOR_FLOW_SCENARIO", "baseline")
	switch scenario {
	case "baseline":
		err = runner.runBaseline(ctx, agentToken)
	case "java-output-rate":
		err = runner.runJavaOutputRate(ctx, agentToken)
	case "monitor-backpressure":
		err = runner.runMonitorBackpressure(ctx, agentToken)
	case "agent-disconnect-closed":
		err = runner.runAgentDisconnectClosed(ctx, agentToken)
	case "agent-heartbeat-timeout-closed":
		err = runner.runAgentHeartbeatTimeoutClosed(ctx, agentToken)
	default:
		err = fmt.Errorf("unsupported SUSUMONITOR_FLOW_SCENARIO")
	}
	if err != nil {
		fail(err.Error())
	}
	fmt.Println("TERMINAL_FLOW_CONTROL_INTEGRATION_OK")
}

// runBaseline verifies Java control limiting, Agent output limiting, and both disconnect paths.
func (r *flowRunner) runBaseline(ctx context.Context, agentToken string) error {
	if err := r.startAgent(ctx, agentToken, getenv("SUSUMONITOR_FLOW_AGENT_RATE", "1048576"), getenv("SUSUMONITOR_FLOW_AGENT_BURST", "1048576")); err != nil {
		return fmt.Errorf("start baseline agent: %w", err)
	}
	if err := r.verifyControlFlood(ctx); err != nil {
		return fmt.Errorf("verify control flood: %w", err)
	}
	if err := r.verifyMonitorDisconnect(ctx); err != nil {
		return fmt.Errorf("verify monitor disconnect: %w", err)
	}
	if err := r.verifyAgentDisconnect(ctx); err != nil {
		return fmt.Errorf("verify agent disconnect: %w", err)
	}
	if err := r.startAgent(ctx, agentToken, "1", "16384"); err != nil {
		return fmt.Errorf("start output-limited agent: %w", err)
	}
	if err := r.verifyOutputClose(ctx, "output_rate_exceeded"); err != nil {
		return fmt.Errorf("verify Agent output limit: %w", err)
	}
	return nil
}

// runJavaOutputRate verifies Java's independently configured output bucket.
func (r *flowRunner) runJavaOutputRate(ctx context.Context, agentToken string) error {
	if getenv("SUSUMONITOR_FLOW_JAVA_OUTPUT_RATE_CONFIGURED", "") != "true" {
		return errors.New("Java output limit scenario requires SUSUMONITOR_FLOW_JAVA_OUTPUT_RATE_CONFIGURED=true")
	}
	if err := r.startAgent(ctx, agentToken, getenv("SUSUMONITOR_FLOW_AGENT_RATE", "67108864"), getenv("SUSUMONITOR_FLOW_AGENT_BURST", "67108864")); err != nil {
		return err
	}
	return r.verifyJavaOutputRate(ctx)
}

// runMonitorBackpressure verifies the observable unreliable-session close status only.
func (r *flowRunner) runMonitorBackpressure(ctx context.Context, agentToken string) error {
	if getenv("SUSUMONITOR_FLOW_MONITOR_BACKPRESSURE_CONFIGURED", "") != "true" {
		return errors.New("monitor backpressure scenario requires SUSUMONITOR_FLOW_MONITOR_BACKPRESSURE_CONFIGURED=true")
	}
	if err := r.startAgent(ctx, agentToken, "1048576", "1048576"); err != nil {
		return err
	}
	return r.verifyMonitorBackpressure(ctx)
}

// startAgent starts one Linux Agent with scenario-specific local output limits.
func (r *flowRunner) startAgent(ctx context.Context, token, rate, burst string) error {
	r.stopAgent()
	if _, err := strconv.Atoi(rate); err != nil {
		return errors.New("agent output rate must be an integer")
	}
	if _, err := strconv.Atoi(burst); err != nil {
		return errors.New("agent output burst must be an integer")
	}
	if err := os.MkdirAll("/tmp/susumonitor-terminal-flow-control", 0700); err != nil {
		return errors.New("agent log directory initialization failed")
	}
	logFile, err := os.OpenFile(agentLogPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return errors.New("agent log file initialization failed")
	}
	if err := logFile.Chmod(0600); err != nil {
		_ = logFile.Close()
		return errors.New("agent log permission initialization failed")
	}
	command := exec.CommandContext(ctx, r.agentBinary)
	command.Stdout = logFile
	command.Stderr = logFile
	command.Env = append(os.Environ(),
		"SUSUMONITOR_BACKEND_URL=ws://172.29.240.1:18081",
		fmt.Sprintf("SUSUMONITOR_SERVER_ID=%d", r.serverID),
		"SUSUMONITOR_AGENT_TOKEN="+token,
		"SUSUMONITOR_TERMINAL_ENABLED=true",
		"SUSUMONITOR_TERMINAL_SHELL=/bin/bash",
		"SUSUMONITOR_TERMINAL_OUTPUT_RATE_BYTES_PER_SECOND="+rate,
		"SUSUMONITOR_TERMINAL_OUTPUT_BURST_BYTES="+burst,
		"SUSUMONITOR_COLLECT_INTERVAL_SECONDS=60",
		"SUSUMONITOR_HEARTBEAT_INTERVAL_SECONDS="+getenv("SUSUMONITOR_FLOW_HEARTBEAT_INTERVAL_SECONDS", "5"),
		"SUSUMONITOR_RECONNECT_INITIAL_SECONDS=1",
		"SUSUMONITOR_RECONNECT_MAX_SECONDS=2",
	)
	if err := command.Start(); err != nil {
		_ = logFile.Close()
		return errors.New("agent start failed")
	}
	r.agent = command
	r.agentLog = logFile
	return waitForAgent(ctx, r.token, r.serverID, "online")
}

// verifyControlFlood sends valid, minimal input frames and asserts Java's stable error code.
func (r *flowRunner) verifyControlFlood(ctx context.Context) error {
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	for index := 0; index < inputBurstFrames; index++ {
		if err := writeInput(ctx, connection, sessionID, []byte("\n")); err != nil {
			return err
		}
	}
	return expectErrorCode(ctx, connection, 42904)
}

// verifyOutputClose asks the shell for non-sensitive zero bytes and checks only the close reason.
func (r *flowRunner) verifyOutputClose(ctx context.Context, expectedReason string) error {
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	if err := writeInput(ctx, connection, sessionID, []byte("head -c 65536 /dev/zero\n")); err != nil {
		return err
	}
	return expectCloseReason(ctx, connection, expectedReason)
}

// verifyJavaOutputRate proves the Java output bucket removed the relay binding. Java deliberately
// does not forward its generated close to Monitor, and no session-metadata REST endpoint exists.
func (r *flowRunner) verifyJavaOutputRate(ctx context.Context) error {
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	if err := writeInput(ctx, connection, sessionID, []byte("head -c 65536 /dev/zero\n")); err != nil {
		return err
	}
	// The raw output is intentionally discarded; the next valid control frame exposes removed binding.
	time.Sleep(time.Second)
	if err := writeInput(ctx, connection, sessionID, []byte("\n")); err != nil {
		return err
	}
	return expectErrorCode(ctx, connection, 40903)
}

// verifyAgentDisconnect proves Java closes the original session after an Agent socket loss.
func (r *flowRunner) verifyAgentDisconnect(ctx context.Context) error {
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	r.stopAgent()
	// agent_status changes only after the Java heartbeat-expiry scan, so it cannot prove immediate closure.
	time.Sleep(time.Second)
	if err := writeInput(ctx, connection, sessionID, []byte("\n")); err != nil {
		return err
	}
	return expectErrorCode(ctx, connection, 40903)
}

// runAgentDisconnectClosed proves the server-generated terminal.closed frame with reason
// agent_disconnected reaches the originating Monitor connection after an Agent socket loss
// (20260813 M1 relay enhancement, end-to-end acceptance).
func (r *flowRunner) runAgentDisconnectClosed(ctx context.Context, agentToken string) error {
	if err := r.startAgent(ctx, agentToken, "1048576", "1048576"); err != nil {
		return err
	}
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	r.stopAgent()
	return expectAgentDisconnectedClose(ctx, connection, r.serverID, sessionID)
}

// runAgentHeartbeatTimeoutClosed proves the same server-generated terminal.closed frame reaches
// the originating Monitor connection when the Java heartbeat scan expires the Agent session while
// the Agent stays connected without sending heartbeats (20260815 relay closeout acceptance).
// The Java validation instance must run with a short AGENT_HEARTBEAT_TIMEOUT_SECONDS and the Agent
// with a heartbeat interval beyond the scenario window so only the scan can close the session.
func (r *flowRunner) runAgentHeartbeatTimeoutClosed(ctx context.Context, agentToken string) error {
	if err := r.startAgent(ctx, agentToken, "1048576", "1048576"); err != nil {
		return err
	}
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	// The scan runs every 30 seconds, so expiry can be observed up to timeout + one scan window.
	return expectAgentDisconnectedCloseWithin(ctx, connection, r.serverID, sessionID, 90*time.Second)
}

// verifyMonitorDisconnect closes the owner socket, then confirms the Agent remains reachable.
// Persisted monitor_disconnected state cannot be queried because no terminal metadata endpoint exists.
func (r *flowRunner) verifyMonitorDisconnect(ctx context.Context) error {
	connection, _, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	if err := connection.Close(websocket.StatusNormalClosure, "monitor_disconnect_test"); err != nil {
		return err
	}
	return waitForAgent(ctx, r.token, r.serverID, "online")
}

// verifyMonitorBackpressure attempts to create a slow Monitor consumer using limited, non-sensitive output.
// Local WSL loopback buffering can prevent the expected close, so this scenario is not a deterministic assertion.
func (r *flowRunner) verifyMonitorBackpressure(ctx context.Context) error {
	observer, err := r.openMonitor(ctx)
	if err != nil {
		return err
	}
	defer observer.Close(websocket.StatusNormalClosure, "flow_control_complete")
	connection, sessionID, err := r.openTerminal(ctx)
	if err != nil {
		return err
	}
	defer connection.Close(websocket.StatusNormalClosure, "flow_control_complete")
	if err := writeInput(ctx, connection, sessionID, []byte("yes X | head -c 67108864\n")); err != nil {
		return err
	}
	time.Sleep(3 * time.Second)
	readCtx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	for {
		var message wsclient.AgentMessage
		err := wsjson.Read(readCtx, connection, &message)
		if closeErr := (*websocket.CloseError)(nil); errors.As(err, &closeErr) {
			if closeErr.Code == monitorUnreliableCloseCode {
				return nil
			}
			return errors.New("monitor websocket closed without unreliable-session status")
		}
		if err != nil {
			return errors.New("monitor backpressure close was not observed")
		}
	}
}

// openMonitor opens an otherwise idle Monitor so the slow terminal owner is a second connection.
func (r *flowRunner) openMonitor(ctx context.Context) (*websocket.Conn, error) {
	ticket, err := issueTicket(ctx, r.token)
	if err != nil {
		return nil, err
	}
	connection, _, err := websocket.Dial(ctx, monitorURL+ticket, nil)
	if err != nil {
		return nil, errors.New("monitor websocket dial failed")
	}
	return connection, nil
}

// openTerminal opens a terminal and returns only its opaque session ID.
func (r *flowRunner) openTerminal(ctx context.Context) (*websocket.Conn, string, error) {
	ticket, err := issueTicket(ctx, r.token)
	if err != nil {
		return nil, "", err
	}
	connection, _, err := websocket.Dial(ctx, monitorURL+ticket, nil)
	if err != nil {
		return nil, "", errors.New("monitor websocket dial failed")
	}
	openMsg := wsclient.NewMessage("terminal.open", map[string]interface{}{
		"server_id": r.serverID, "cols": 80, "rows": 24,
	})
	if err := wsjson.Write(ctx, connection, openMsg); err != nil {
		_ = connection.Close(websocket.StatusNormalClosure, "open_failed")
		return nil, "", err
	}
	message, err := readType(ctx, connection, "terminal.opened")
	if err != nil {
		_ = connection.Close(websocket.StatusNormalClosure, "open_failed")
		return nil, "", err
	}
	var payload struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(message.Payload, &payload); err != nil || payload.SessionID == "" {
		return nil, "", errors.New("terminal.opened did not contain a session ID")
	}
	return connection, payload.SessionID, nil
}

// writeInput encodes test commands without logging their content.
func writeInput(ctx context.Context, connection *websocket.Conn, sessionID string, data []byte) error {
	return wsjson.Write(ctx, connection, wsclient.NewMessage("terminal.input", map[string]interface{}{
		"session_id": sessionID, "data": base64.StdEncoding.EncodeToString(data),
	}))
}

// expectErrorCode waits for the fixed protocol error code without exposing error text.
func expectErrorCode(ctx context.Context, connection *websocket.Conn, expectedCode int) error {
	message, err := readType(ctx, connection, "error")
	if err != nil {
		return err
	}
	var payload struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(message.Payload, &payload); err != nil || payload.Code != expectedCode {
		return errors.New("unexpected terminal error code")
	}
	return nil
}

// expectCloseReason waits for a server-generated terminal close with the fixed expected reason.
func expectCloseReason(ctx context.Context, connection *websocket.Conn, expectedReason string) error {
	message, err := readType(ctx, connection, "terminal.closed")
	if err != nil {
		return err
	}
	var payload struct {
		Reason string `json:"reason"`
	}
	if err := json.Unmarshal(message.Payload, &payload); err != nil || payload.Reason != expectedReason {
		return errors.New("unexpected terminal close reason")
	}
	return nil
}

// expectAgentDisconnectedClose asserts the full server-generated terminal.closed envelope for an
// Agent socket loss: UUID message_id, UTC ISO-8601 timestamp, and the fixed agent_disconnected
// payload matching the exact session that was opened.
func expectAgentDisconnectedClose(ctx context.Context, connection *websocket.Conn, serverID int64, sessionID string) error {
	return expectAgentDisconnectedCloseWithin(ctx, connection, serverID, sessionID, 20*time.Second)
}

// expectAgentDisconnectedCloseWithin is expectAgentDisconnectedClose with a caller-chosen frame wait.
func expectAgentDisconnectedCloseWithin(ctx context.Context, connection *websocket.Conn, serverID int64, sessionID string, timeout time.Duration) error {
	message, err := readTypeWithin(ctx, connection, "terminal.closed", timeout)
	if err != nil {
		return err
	}
	if !isUUIDv4(message.MessageID) {
		return errors.New("terminal.closed message_id is not a UUID v4")
	}
	if _, err := time.Parse(time.RFC3339, message.Timestamp); err != nil || !strings.HasSuffix(message.Timestamp, "Z") {
		return errors.New("terminal.closed timestamp is not UTC ISO-8601")
	}
	var payload struct {
		ServerID  int64  `json:"server_id"`
		SessionID string `json:"session_id"`
		Reason    string `json:"reason"`
	}
	if err := json.Unmarshal(message.Payload, &payload); err != nil {
		return errors.New("terminal.closed payload was invalid")
	}
	if payload.ServerID != serverID || payload.SessionID != sessionID || payload.Reason != "agent_disconnected" {
		return errors.New("terminal.closed payload does not match the agent_disconnected contract")
	}
	return nil
}

// isUUIDv4 reports whether the value is a canonical lower-case UUID v4 string.
func isUUIDv4(value string) bool {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' || value[14] != '4' {
		return false
	}
	for index := 0; index < len(value); index++ {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			continue
		}
		if !((value[index] >= '0' && value[index] <= '9') || (value[index] >= 'a' && value[index] <= 'f')) {
			return false
		}
	}
	return true
}

// readType ignores terminal data and does not decode or print it.
// If an error frame arrives before the expected type, its protocol code is surfaced
// so a server-side rejection is not masked as a read timeout.
func readType(parent context.Context, connection *websocket.Conn, expectedType string) (wsclient.AgentMessage, error) {
	return readTypeWithin(parent, connection, expectedType, 20*time.Second)
}

// readTypeWithin is readType with a caller-chosen frame wait for slow server-side paths.
func readTypeWithin(parent context.Context, connection *websocket.Conn, expectedType string, timeout time.Duration) (wsclient.AgentMessage, error) {
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()
	for {
		var message wsclient.AgentMessage
		if err := wsjson.Read(ctx, connection, &message); err != nil {
			return wsclient.AgentMessage{}, err
		}
		if message.Type == expectedType {
			return message, nil
		}
		if message.Type == "error" {
			var errPayload struct {
				Code int `json:"code"`
			}
			_ = json.Unmarshal(message.Payload, &errPayload)
			return wsclient.AgentMessage{}, fmt.Errorf("received error frame: code=%d", errPayload.Code)
		}
	}
}

// login obtains a JWT only in process memory.
func login(ctx context.Context, username, password string) (string, error) {
	var data struct {
		Token string `json:"token"`
	}
	if err := request(ctx, http.MethodPost, "/api/auth/login", "", map[string]string{"username": username, "password": password}, &data); err != nil || data.Token == "" {
		return "", errors.New("login failed")
	}
	return data.Token, nil
}

// verifyAdmin blocks side effects for users without isolated-server administration rights.
func verifyAdmin(ctx context.Context, token string) error {
	var data struct {
		Role         string `json:"role"`
		ReviewStatus string `json:"reviewStatus"`
	}
	if err := request(ctx, http.MethodGet, "/api/auth/me", token, nil, &data); err != nil || data.Role != "admin" || data.ReviewStatus != "approved" {
		return errors.New("integration user is not an approved admin")
	}
	return nil
}

// createServer creates isolated metadata with a random non-reusable placeholder credential.
func createServer(ctx context.Context, token string) (int64, error) {
	placeholder := make([]byte, 24)
	if _, err := rand.Read(placeholder); err != nil {
		return 0, err
	}
	runID := strconv.FormatInt(time.Now().UnixNano(), 10)
	var data serverData
	payload := map[string]interface{}{"name": serverNamePrefix + runID, "host": "flow-" + runID + ".invalid", "description": "isolated terminal flow-control integration", "ssh_host": "127.0.0.1", "ssh_port": 22, "ssh_user": "unused", "ssh_auth_type": "password", "ssh_password": base64.RawURLEncoding.EncodeToString(placeholder)}
	if err := request(ctx, http.MethodPost, "/api/servers", token, payload, &data); err != nil || data.ID <= 0 {
		return 0, errors.New("server creation failed")
	}
	return data.ID, nil
}

// registerAgent creates a temporary Agent Token without logging it.
func registerAgent(ctx context.Context, token string, serverID int64) (string, error) {
	var data agentTokenData
	if err := request(ctx, http.MethodPost, fmt.Sprintf("/api/servers/%d/agent/register", serverID), token, nil, &data); err != nil || data.AgentToken == "" {
		return "", errors.New("agent registration failed")
	}
	return data.AgentToken, nil
}

// issueTicket obtains a single-use Monitor ticket in process memory.
func issueTicket(ctx context.Context, token string) (string, error) {
	var data monitorTicketData
	if err := request(ctx, http.MethodPost, "/api/ws/monitor-ticket", token, nil, &data); err != nil || data.Ticket == "" {
		return "", errors.New("monitor ticket request failed")
	}
	return data.Ticket, nil
}

// waitForAgent polls the public server status without logging its response.
func waitForAgent(ctx context.Context, token string, serverID int64, expectedStatus string) error {
	for {
		var data struct {
			AgentStatus string `json:"agent_status"`
		}
		if err := request(ctx, http.MethodGet, fmt.Sprintf("/api/servers/%d", serverID), token, nil, &data); err != nil {
			return err
		}
		if data.AgentStatus == expectedStatus {
			return nil
		}
		select {
		case <-ctx.Done():
			return errors.New("agent status transition was not observed")
		case <-time.After(time.Second):
		}
	}
}

// request keeps API response content, including any sensitive fields, out of output.
func request(ctx context.Context, method, path, token string, payload, destination interface{}) error {
	var body io.Reader
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		body = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, backendURL+path, body)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	var envelope apiResponse
	if err := json.NewDecoder(io.LimitReader(response.Body, 64*1024)).Decode(&envelope); err != nil || response.StatusCode < 200 || response.StatusCode >= 300 || envelope.Code != 0 {
		return errors.New("API request failed")
	}
	if destination != nil && json.Unmarshal(envelope.Data, destination) != nil {
		return errors.New("API response payload was invalid")
	}
	return nil
}

// revokeAgent invalidates the temporary token on every exit path.
func revokeAgent(ctx context.Context, token string, serverID int64) {
	_ = request(ctx, http.MethodDelete, fmt.Sprintf("/api/servers/%d/agent/revoke", serverID), token, nil, nil)
}

// stopAgent terminates the current Agent before its token is revoked.
func (r *flowRunner) stopAgent() {
	if r.agent == nil || r.agent.Process == nil {
		return
	}
	_ = r.agent.Process.Signal(os.Interrupt)
	done := make(chan struct{})
	go func() { _ = r.agent.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		_ = r.agent.Process.Kill()
		<-done
	}
	r.agent = nil
	if r.agentLog != nil {
		_ = r.agentLog.Close()
		r.agentLog = nil
	}
	_ = os.Remove(agentLogPath)
}

// getenv reads an optional runner setting without emitting its value.
func getenv(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

// unset clears a process-scoped secret.
func unset(name string) { _ = os.Unsetenv(name) }

// fail emits only fixed, non-sensitive diagnostic categories.
func fail(message string) {
	fmt.Fprintln(os.Stderr, "TERMINAL_FLOW_CONTROL_INTEGRATION_FAILED:", strings.ReplaceAll(message, "\n", " "))
	os.Exit(1)
}
