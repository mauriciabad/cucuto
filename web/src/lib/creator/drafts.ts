// Drafts that survive a reload (#191): the Create page saves the pack being made to IndexedDB after every change, so
// an offline session is never lost, and lists saved drafts to resume or delete. Everything stays in this browser:
// nothing is uploaded, and clearing the site's data deletes the drafts. The store also hands a pack file from the
// /open/ page (the PWA file handler) to the creator.
import type { PackState } from './pack.js';

const DB_NAME = 'cucuto-creator';
const DB_VERSION = 1;
/** Whole drafts, by ID. */
const DRAFTS = 'drafts';
/** Small summaries for the list, by draft ID, so listing never loads every clip. */
const SUMMARIES = 'summaries';
/** Pack files waiting to be opened by the creator, by key. */
const PENDING = 'pending';

/**
 * Sources longer than this are not saved with a draft (a 6-minute song is 69 MB of floats): after a reload, such a
 * clip is edited again from the processed clip instead of the whole song.
 */
export const MAX_SAVED_SOURCE_SAMPLES = 60 * 48_000;

export interface DraftSummary {
	id: string;
	name: string;
	characters: number;
	clips: number;
	/** Milliseconds since the epoch. */
	updatedAt: number;
	/** The sprite of the first character, or the cover, as a data: URL. */
	thumbnail: string | null;
}

interface StoredDraft {
	id: string;
	pack: PackState;
}

let opening: Promise<IDBDatabase> | null = null;

/** Whether this browser can keep drafts (IndexedDB can be missing or blocked, for example in private windows). */
export const canSaveDrafts = () => typeof indexedDB !== 'undefined';

function db(): Promise<IDBDatabase> {
	opening ??= new Promise<IDBDatabase>((resolve, reject) => {
		const request = indexedDB.open(DB_NAME, DB_VERSION);
		request.onupgradeneeded = () => {
			const database = request.result;
			for (const store of [DRAFTS, SUMMARIES]) {
				if (!database.objectStoreNames.contains(store)) {
					database.createObjectStore(store, { keyPath: 'id' });
				}
			}
			if (!database.objectStoreNames.contains(PENDING)) database.createObjectStore(PENDING);
		};
		request.onsuccess = () => resolve(request.result);
		request.onerror = () => reject(request.error ?? new Error('IndexedDB unavailable'));
		request.onblocked = () => reject(new Error('IndexedDB blocked'));
	}).catch((error: unknown) => {
		opening = null;
		throw error;
	});
	return opening;
}

function done(transaction: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		transaction.oncomplete = () => resolve();
		transaction.onerror = () => reject(transaction.error ?? new Error('IndexedDB error'));
		transaction.onabort = () => reject(transaction.error ?? new Error('IndexedDB aborted'));
	});
}

function result<T>(request: IDBRequest<T>): Promise<T> {
	return new Promise((resolve, reject) => {
		request.onsuccess = () => resolve(request.result);
		request.onerror = () => reject(request.error ?? new Error('IndexedDB error'));
	});
}

/** Whether a draft has anything worth keeping. */
export function hasContent(pack: PackState): boolean {
	return (
		pack.name.trim() !== '' ||
		pack.description.trim() !== '' ||
		pack.cover !== null ||
		pack.origin !== null ||
		pack.characters.some(
			(c) =>
				c.name.trim() !== '' || c.sprite !== null || c.clips.length > 0 || c.ringtones.length > 0
		)
	);
}

/** `pack` as stored: long sources are left out (see [MAX_SAVED_SOURCE_SAMPLES]). */
function storable(pack: PackState): PackState {
	return {
		...pack,
		characters: pack.characters.map((c) => ({
			...c,
			clips: c.clips.map((clip) => ({
				...clip,
				source: clip.source && clip.source.length <= MAX_SAVED_SOURCE_SAMPLES ? clip.source : null
			}))
		}))
	};
}

function summary(id: string, pack: PackState, updatedAt: number): DraftSummary {
	const sprite = pack.characters.find((c) => c.sprite)?.sprite ?? pack.cover;
	return {
		id,
		name: pack.name,
		characters: pack.characters.length,
		clips: pack.characters.reduce((n, c) => n + c.clips.length, 0),
		updatedAt,
		thumbnail: sprite?.preview ?? null
	};
}

/** Saves `pack` (a plain snapshot, not a Svelte proxy) as draft `id`. */
export async function saveDraft(id: string, pack: PackState, now = Date.now()): Promise<void> {
	const database = await db();
	const transaction = database.transaction([DRAFTS, SUMMARIES], 'readwrite');
	transaction.objectStore(DRAFTS).put({ id, pack: storable(pack) } satisfies StoredDraft);
	transaction.objectStore(SUMMARIES).put(summary(id, pack, now));
	await done(transaction);
}

/** Every saved draft, most recently changed first. */
export async function listDrafts(): Promise<DraftSummary[]> {
	const database = await db();
	const transaction = database.transaction(SUMMARIES, 'readonly');
	const all = await result(
		transaction.objectStore(SUMMARIES).getAll() as IDBRequest<DraftSummary[]>
	);
	return all.sort((a, b) => b.updatedAt - a.updatedAt);
}

/** The saved draft `id`, or null when it is gone. */
export async function loadDraft(id: string): Promise<PackState | null> {
	const database = await db();
	const transaction = database.transaction(DRAFTS, 'readonly');
	const stored = await result(
		transaction.objectStore(DRAFTS).get(id) as IDBRequest<StoredDraft | undefined>
	);
	return stored?.pack ?? null;
}

export async function deleteDraft(id: string): Promise<void> {
	const database = await db();
	const transaction = database.transaction([DRAFTS, SUMMARIES], 'readwrite');
	transaction.objectStore(DRAFTS).delete(id);
	transaction.objectStore(SUMMARIES).delete(id);
	await done(transaction);
}

/** Keeps a pack file for the creator to open (from /open/): the creator page reads it with [takePendingFile]. */
export async function putPendingFile(file: File): Promise<string> {
	const key = `open-${Date.now().toString(36)}`;
	// Read first: a transaction closes as soon as its task awaits anything else.
	const bytes = new Uint8Array(await file.arrayBuffer());
	const database = await db();
	const transaction = database.transaction(PENDING, 'readwrite');
	transaction.objectStore(PENDING).put({ name: file.name, bytes }, key);
	await done(transaction);
	return key;
}

/** The pack file kept under `key`, removed from the store; null when it is gone. */
export async function takePendingFile(
	key: string
): Promise<{ name: string; bytes: Uint8Array } | null> {
	const database = await db();
	const transaction = database.transaction(PENDING, 'readwrite');
	const store = transaction.objectStore(PENDING);
	const file = await result(
		store.get(key) as IDBRequest<{ name: string; bytes: Uint8Array } | undefined>
	);
	store.delete(key);
	await done(transaction);
	return file ?? null;
}
