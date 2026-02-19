const AriClient = require('ari-client');
const dgram = require('dgram');
const mulaw = require('mulaw-js');
const WebSocket = require('ws');

const ARI_URL = 'http://localhost:8088';
const ARI_USER = 'aiuser';
const ARI_PASS = 'strongpassword';
const RTP_PORT = 5004;
const LOCAL_IP = '127.0.0.1';
const AGENT_ID = 'agent_6101khw0gytpeh2bbvfb2bcdjd05';

process.on('unhandledRejection', (err) => {
  console.warn('⚠️  Unhandled:', err.message || err);
});

let callState = null;
const rtpServer = dgram.createSocket('udp4');
const SILENCE = 0xFF; // ulaw silence byte

rtpServer.on('listening', () => console.log('🎧 RTP on', rtpServer.address()));

rtpServer.on('message', (msg, rinfo) => {
  if (msg.length <= 12 || !callState) return;
  callState.asteriskHost = rinfo.address;
  callState.asteriskPort = rinfo.port;

  const ulawPayload = msg.slice(12);

  if (callState.ws && callState.ws.readyState === WebSocket.OPEN && callState.ready) {
    // Decode ulaw 8kHz -> PCM 16-bit
    const pcm8k = new Int16Array(ulawPayload.length);
    for (let i = 0; i < ulawPayload.length; i++) {
      pcm8k[i] = mulaw.decodeSample(ulawPayload[i]);
    }

    // Upsample 8kHz -> 16kHz (linear interpolation)
    const pcm16k = new Int16Array(pcm8k.length * 2);
    for (let i = 0; i < pcm8k.length; i++) {
      pcm16k[i * 2] = pcm8k[i];
      // Interpolate between samples for smoother upsampling
      const next = (i + 1 < pcm8k.length) ? pcm8k[i + 1] : pcm8k[i];
      pcm16k[i * 2 + 1] = Math.round((pcm8k[i] + next) / 2);
    }

    const pcmBuf = Buffer.from(pcm16k.buffer);
    callState.ws.send(JSON.stringify({
      user_audio_chunk: pcmBuf.toString('base64')
    }));
  }
});

rtpServer.on('error', (err) => console.error('RTP error:', err.message));
rtpServer.bind(RTP_PORT, '0.0.0.0');

function buildRtpPacket(payload, seqNum, timestamp, ssrc) {
  const h = Buffer.alloc(12);
  h[0] = 0x80;
  h[1] = 0x00; // PT=0 PCMU
  h.writeUInt16BE(seqNum & 0xFFFF, 2);
  h.writeUInt32BE(timestamp >>> 0, 4);
  h.writeUInt32BE(ssrc >>> 0, 8);
  return Buffer.concat([h, payload]);
}

function connectElevenLabs() {
  const ws = new WebSocket(`wss://api.elevenlabs.io/v1/convai/conversation?agent_id=${AGENT_ID}`);
  console.log('🔌 Connecting to ElevenLabs...');

  let rtpSeq = 0, rtpTs = 0;
  const rtpSSRC = Math.floor(Math.random() * 0xFFFFFFFF);
  let audioQueue = Buffer.alloc(0);
  let sendTimer = null;
  let sent = 0;
  let silentPkts = 0;
  let hasAudio = false; // track if we've ever had audio

  function startAudioSender() {
    if (sendTimer) return;
    sendTimer = setInterval(() => {
      if (!callState || !callState.asteriskHost) return;

      let chunk;

      if (audioQueue.length >= 480) {
        chunk = audioQueue.slice(0, 160);
        audioQueue = audioQueue.slice(160);
        hasAudio = true;
      } else {
        // always send silence if queue is empty/short (keeps RTP stable)
        chunk = Buffer.alloc(160, SILENCE);

        // count underruns so we can see if audio queue is starving
        callState.underruns = (callState.underruns || 0) + 1;

        // print sometimes (not too much)
        if (callState.underruns <= 5 || callState.underruns % 50 === 0) {
          console.log(`⚠️ underrun #${callState.underruns} (q:${audioQueue.length})`);
        }
      }

      const pkt = buildRtpPacket(chunk, rtpSeq, rtpTs, rtpSSRC);
      rtpServer.send(pkt, callState.asteriskPort, callState.asteriskHost);
      rtpSeq = (rtpSeq + 1) & 0xFFFF;
      rtpTs += 160;
      sent++;
      if (sent <= 3 || sent % 200 === 0) {
        console.log(`📤 RTP #${sent} -> ${callState.asteriskHost}:${callState.asteriskPort} (q:${audioQueue.length})`);
      }
    }, 20);
  }

  ws.on('open', () => console.log('🟢 ElevenLabs connected'));

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());

      if (msg.type === 'conversation_initiation_metadata') {
        const c = msg.conversation_initiation_metadata_event;
        console.log(`📋 Audio: out=${c.agent_output_audio_format} in=${c.user_input_audio_format}`);
        if (callState) callState.ready = true;
        startAudioSender();
      }

      if (msg.type === 'audio') {
        const b64 = msg.audio_event?.audio_base_64;
        if (b64 && callState) {
          const pcmBuf = Buffer.from(b64, 'base64');
          const sampleCount = pcmBuf.length / 2;

          // Parse PCM 16-bit LE samples
          const pcm16k = new Array(sampleCount);
          for (let i = 0; i < sampleCount; i++) {
            pcm16k[i] = pcmBuf.readInt16LE(i * 2);
          }

          // Downsample 16kHz -> 8kHz with averaging (anti-alias filter)
          const pcm8kLen = Math.floor(sampleCount / 2);
          const ulawBuf = Buffer.alloc(pcm8kLen);
          for (let i = 0; i < pcm8kLen; i++) {
            // Average adjacent samples instead of dropping (simple low-pass)
            const avg = Math.round((pcm16k[i * 2] + pcm16k[i * 2 + 1]) / 2);
            ulawBuf[i] = mulaw.encodeSample(avg);
          }

          audioQueue = Buffer.concat([audioQueue, ulawBuf]);
          silentPkts = 0; // reset silence counter when new audio arrives

          if (!callState.logged) {
            callState.logged = true;
            let nonSilent = 0;
            for (let i = 0; i < ulawBuf.length; i++) {
              if (ulawBuf[i] !== 0xFF && ulawBuf[i] !== 0x7F) nonSilent++;
            }
            console.log(`🔊 PCM ${pcmBuf.length}B -> ulaw ${ulawBuf.length}B (${nonSilent} non-silent)`);
          }
        }
      }

      if (msg.type === 'agent_response')
        console.log('🤖 Agent:', msg.agent_response_event?.agent_response || '');
      if (msg.type === 'user_transcription')
        console.log('👤 User:', msg.user_transcription_event?.user_transcript || '');
    } catch (e) { }
  });

  ws.on('error', (err) => console.error('❌ ElevenLabs error:', err.message));
  ws.on('close', (code) => {
    console.log(`🔴 ElevenLabs closed: ${code} (${sent} pkts)`);
    if (sendTimer) { clearInterval(sendTimer); sendTimer = null; }
  });

  return ws;
}

AriClient.connect(ARI_URL, ARI_USER, ARI_PASS)
  .then((ari) => {
    console.log('✅ ARI connected');

    ari.on('StasisStart', async (event, channel) => {
      const n = channel.name || '';
      if (n.startsWith('UnicastRTP/') || n.startsWith('ExternalMedia/')) {
        console.log(`⏭️  Skip: ${n}`);
        return;
      }
      console.log(`📞 Call from ${channel.caller.number}`);

      try {
        const bridge = ari.Bridge();
        await bridge.create({ type: 'mixing' });
        await bridge.addChannel({ channel: channel.id });

        const ext = await ari.channels.externalMedia({
          app: 'ai_bridge',
          external_host: `${LOCAL_IP}:${RTP_PORT}`,
          format: 'ulaw',
          encapsulation: 'rtp',
          transport: 'udp',
          direction: 'both',
          connection_type: 'client'
        });

        await bridge.addChannel({ channel: ext.id });
        console.log('🔗 Bridged');

        const ws = connectElevenLabs();
        let cleaned = false;
        callState = {
          ws, ready: false, logged: false,
          asteriskHost: null, asteriskPort: null,
          bridge, external: ext, channel
        };

        const cleanup = async () => {
          if (cleaned) return;
          cleaned = true;
          console.log('🧹 Cleanup');
          if (callState) { try { callState.ws.close(); } catch (e) { } callState = null; }
          try { await ext.hangup(); } catch (e) { }
          try { await bridge.destroy(); } catch (e) { }
        };

        channel.on('StasisEnd', () => { console.log('📴 Caller left'); cleanup(); });
        ext.on('StasisEnd', () => { console.log('📴 Ext ended'); cleanup(); });
      } catch (err) {
        console.error('❌ Error:', err.message);
        try { await channel.hangup(); } catch (e) { }
      }
    });

    ari.on('StasisEnd', () => { });
    ari.start('ai_bridge');
    console.log('🚀 Ready');
  })
  .catch((err) => { console.error('❌ ARI failed:', err.message); process.exit(1); });
