import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

function wrapForTmux(sequence: string): string {
  if (!process.env.TMUX) return sequence;
  // tmux consumes OSC 9 unless it is sent through DCS passthrough. Escape the
  // ESC bytes inside the payload as required by tmux's passthrough protocol.
  return `\x1bPtmux;${sequence.split("\x1b").join("\x1b\x1b")}\x1b\\`;
}

function notifyChuchu(): void {
  process.stdout.write(wrapForTmux("\x1b]9;chuchu-agent-done;0\x07"));
}

export default function (pi: ExtensionAPI): void {
  // agent_settled fires once the turn has finished and Pi has no retry,
  // compaction, or follow-up work left to run.
  pi.on("agent_settled", async () => {
    notifyChuchu();
  });
}
