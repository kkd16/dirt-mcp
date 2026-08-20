const SERVICE = 'dirt-mcp-stdio';
const MAX_STRING_LENGTH = 2_048;
const TRUNCATION_SUFFIX = '...[truncated]';
const REDACTED = '[redacted]';
const SENSITIVE_FIELD = /(api_?key|access_?key|authorization|bearer|credential|password|secret|token)/i;
const RESERVED_FIELDS = new Set(['timestamp', 'level', 'service', 'event', 'message', 'pid']);

export type LogLevel = 'debug' | 'info' | 'warning' | 'error';
export type LogValue = string | number | boolean | null;
export type LogFields = Readonly<Record<string, LogValue | undefined>>;
export type LogSink = (line: string) => void;

export interface DirtLogger {
  child(context: LogFields): DirtLogger;
  debug(event: string, message: string, fields?: LogFields): void;
  info(event: string, message: string, fields?: LogFields): void;
  warning(event: string, message: string, fields?: LogFields): void;
  error(event: string, message: string, fields?: LogFields): void;
}

export function createLogger(component: string, sink: LogSink = stderrSink): DirtLogger {
  return loggerWithContext(sink, { component });
}

export function safeErrorFields(error: unknown): LogFields {
  const errorType = safeErrorType(error);
  const stackLocations = error instanceof Error ? safeStackLocations(error) : undefined;
  return {
    error_type: errorType,
    ...(stackLocations === undefined ? {} : { stack_locations: stackLocations }),
  };
}

function loggerWithContext(sink: LogSink, context: LogFields): DirtLogger {
  const emit = (level: LogLevel, event: string, message: string, fields: LogFields = {}): void => {
    const details = sanitizeFields({ ...context, ...fields });
    const component = typeof details.component === 'string' ? details.component : 'runtime';
    delete details.component;
    const record = {
      timestamp: new Date().toISOString(),
      level,
      service: SERVICE,
      component,
      event: bounded(event),
      message: bounded(message),
      pid: process.pid,
      ...details,
    };
    sink(`${singleLineJson(record)}\n`);
  };

  return {
    child(additionalContext) {
      return loggerWithContext(sink, { ...context, ...additionalContext });
    },
    debug(event, message, fields) {
      emit('debug', event, message, fields);
    },
    info(event, message, fields) {
      emit('info', event, message, fields);
    },
    warning(event, message, fields) {
      emit('warning', event, message, fields);
    },
    error(event, message, fields) {
      emit('error', event, message, fields);
    },
  };
}

function sanitizeFields(fields: LogFields): Record<string, LogValue> {
  const entries: [string, LogValue][] = [];
  for (const [key, value] of Object.entries(fields)) {
    if (value === undefined || RESERVED_FIELDS.has(key)) continue;
    if (SENSITIVE_FIELD.test(key)) {
      entries.push([key, REDACTED]);
    } else if (typeof value === 'string') {
      entries.push([key, bounded(value)]);
    } else if (typeof value === 'number' && !Number.isFinite(value)) {
      entries.push([key, null]);
    } else {
      entries.push([key, value]);
    }
  }
  return Object.fromEntries(entries);
}

function safeErrorType(error: unknown): string {
  if (error instanceof AggregateError) return 'AggregateError';
  if (error instanceof EvalError) return 'EvalError';
  if (error instanceof RangeError) return 'RangeError';
  if (error instanceof ReferenceError) return 'ReferenceError';
  if (error instanceof SyntaxError) return 'SyntaxError';
  if (error instanceof TypeError) return 'TypeError';
  if (error instanceof URIError) return 'URIError';
  return error instanceof Error ? 'Error' : typeof error;
}

function safeStackLocations(error: Error): string | undefined {
  let stack: string | undefined;
  let renderedHeader: string;
  try {
    stack = error.stack;
    renderedHeader = Error.prototype.toString.call(error);
  } catch {
    return undefined;
  }
  if (stack === undefined) return undefined;
  if (!stack.startsWith(renderedHeader)) return undefined;
  const locations = stack
    .slice(renderedHeader.length)
    .split(/\r?\n/u)
    .flatMap((line) => {
      const match = /^at (?:.+? \()?((?:file:\/\/|node:|\/)[^()\s]+:\d+:\d+)\)?$/u.exec(line.trim());
      return match?.[1] === undefined ? [] : [match[1]];
    })
    .slice(0, 8);
  return locations.length === 0 ? undefined : locations.join(';');
}

function bounded(value: string): string {
  if (value.length <= MAX_STRING_LENGTH) return value;
  return `${value.slice(0, MAX_STRING_LENGTH - TRUNCATION_SUFFIX.length)}${TRUNCATION_SUFFIX}`;
}

function singleLineJson(value: unknown): string {
  return JSON.stringify(value).replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
}

function stderrSink(line: string): void {
  process.stderr.write(line);
}
