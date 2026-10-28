import { Envelope } from '../types/contracts.js';

export interface IResumeBuffer {
  push(frame: Envelope): void;
  replay(fromSeq: number): { frames: Envelope[]; gapDetected: boolean };
  size(): number;
  clear(): void;
}

/**
 * High-performance bounded circular resume buffer.
 * Keeps the last `capacity` frames (default 500) per connection session.
 */
export class InMemoryResumeBuffer implements IResumeBuffer {
  private readonly capacity: number;
  private readonly buffer: Envelope[] = [];
  private oldestSeq: number = 1;

  constructor(capacity: number = 500) {
    this.capacity = capacity;
  }

  push(frame: Envelope): void {
    if (this.buffer.length >= this.capacity) {
      this.buffer.shift();
    }
    this.buffer.push(frame);
    if (this.buffer.length > 0) {
      this.oldestSeq = this.buffer[0].seq;
    }
  }

  /**
   * Replays frames from `fromSeq + 1` up to the latest sequence.
   * If fromSeq is older than `oldestSeq - 1`, a gap is detected.
   */
  replay(fromSeq: number): { frames: Envelope[]; gapDetected: boolean } {
    if (this.buffer.length === 0) {
      return { frames: [], gapDetected: false };
    }

    const latestSeq = this.buffer[this.buffer.length - 1].seq;
    if (fromSeq >= latestSeq) {
      return { frames: [], gapDetected: false };
    }

    if (fromSeq < this.oldestSeq - 1) {
      // Requested sequence dropped from window -> gap detected!
      return { frames: [], gapDetected: true };
    }

    const framesToReplay = this.buffer.filter(f => f.seq > fromSeq);
    return { frames: framesToReplay, gapDetected: false };
  }

  size(): number {
    return this.buffer.length;
  }

  clear(): void {
    this.buffer.length = 0;
  }
}
