// Минимальный адаптер для текущего static/app.js.
// Идея: D-ID/WebRTC остаётся во frontend, а текст генерируется Kotlin backend через WebSocket.

let trainingSessionId = null;
let trainingSocket = null;
let currentGenerationId = 0;
let currentAssistantText = "";

async function connectTrainingBackend() {
  const response = await fetch("/api/sessions", { method: "POST" });
  if (!response.ok) throw new Error("Не удалось создать training session");
  const session = await response.json();
  trainingSessionId = session.sessionId;

  const wsUrl = new URL(session.websocketUrl);
  // Если frontend и backend на разных origin, замените host/port здесь или задайте reverse proxy.
  trainingSocket = new WebSocket(wsUrl);

  trainingSocket.onmessage = async (event) => {
    const message = JSON.parse(event.data);

    if (message.type === "generation_started") {
      currentAssistantText = "";
      return;
    }

    if (message.type === "assistant_delta" && message.generationId === currentGenerationId) {
      currentAssistantText += message.delta;
      // В UI можно показывать потоковые субтитры.
      status.textContent = currentAssistantText;
      return;
    }

    if (message.type === "assistant_segment" && message.generationId === currentGenerationId) {
      // Здесь подключается очередь D-ID speech. Не вызывайте speak() параллельно на каждом token delta.
      // Для Fluent/V4 можно использовать возможности очереди SDK; для Legacy V2/V3
      // запускайте следующий segment после окончания предыдущего видео.
      console.log("speech segment", message.segmentIndex, message.text);
      return;
    }

    if (message.type === "assistant_completed" && message.generationId === currentGenerationId) {
      console.log("full assistant answer", message.text);
      return;
    }

    if (message.type === "generation_cancelled") {
      currentAssistantText = "";
    }
  };
}

async function askTrainer(userText) {
  if (!trainingSocket || trainingSocket.readyState !== WebSocket.OPEN) {
    throw new Error("Training WebSocket is not connected");
  }

  // Локально инвалидируем предыдущую генерацию до сетевого round trip.
  currentGenerationId += 1;
  currentAssistantText = "";

  // D-ID interrupt поддерживается не всеми типами аватаров, поэтому оставляем fallback.
  try {
    if (agentManager?.interrupt) await agentManager.interrupt({ type: "click" });
  } catch (_) {
    video.pause();
  }

  trainingSocket.send(JSON.stringify({
    type: "user_message",
    generationId: currentGenerationId,
    text: userText,
    clientSentAt: new Date().toISOString()
  }));
}

async function finishTraining() {
  if (!trainingSessionId) return null;
  const response = await fetch(`/api/sessions/${trainingSessionId}/finish`, { method: "POST" });
  if (!response.ok) throw new Error("Не удалось завершить тренировку");
  return response.json();
}
