import { useState } from "react";
import { api, ChatResult } from "../../api/client";
import { ResultsGrid } from "../QueryTab/ResultsGrid";

type Turn = {
  question: string;
  sql?: string;
  result?: ChatResult;
  error?: string;
};

export function ChatTab({ schemas }: { schemas: string[] }) {
  const [schema, setSchema] = useState<string>(schemas[0] ?? "");
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<Turn[]>([]);
  const [asking, setAsking] = useState(false);

  async function ask() {
    if (!schema || !question.trim()) return;
    const q = question.trim();
    setQuestion("");
    setAsking(true);
    try {
      const result = await api.chat(schema, q);
      setTurns((prev) => [...prev, { question: q, sql: result.sql, result }]);
    } catch (e) {
      setTurns((prev) => [...prev, { question: q, error: (e as Error).message }]);
    } finally {
      setAsking(false);
    }
  }

  return (
    <div className="chat-tab">
      <div className="chat-controls">
        <label htmlFor="chat-schema">Schema:</label>
        <select id="chat-schema" value={schema} onChange={(e) => setSchema(e.target.value)}>
          {schemas.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      <div className="chat-transcript">
        {turns.map((turn, i) => (
          <div key={i} className="chat-turn">
            <p className="chat-question">{turn.question}</p>
            {turn.sql && <pre className="chat-sql">{turn.sql}</pre>}
            {turn.result && <ResultsGrid result={turn.result} />}
            {turn.error && <p className="error">{turn.error}</p>}
          </div>
        ))}
        {turns.length === 0 && (
          <p className="hint">Ask a question about the selected schema's data.</p>
        )}
      </div>

      <form
        className="chat-input"
        onSubmit={(e) => {
          e.preventDefault();
          ask();
        }}
      >
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="e.g. how many orders were placed last week?"
          disabled={asking}
        />
        <button type="submit" className="btn" disabled={asking || !question.trim() || !schema}>
          {asking ? "Thinking..." : "Ask"}
        </button>
      </form>
    </div>
  );
}
