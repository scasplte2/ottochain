// Fixed, deterministic ids for the bike-shop run — never crypto.randomUUID(), so the film's evidence
// capture and every assertion observe the SAME instances across mint → move → assert.
export const CREDIT_ID = 'b1ce0000-0000-4000-8000-00000000c000';
export const CREDIT_SHARDS = [1, 2, 3, 4, 5].map(i => `b1ce0000-0000-4000-8000-00000000c00${i}`);
export const PADS_ID = 'b1ce0000-0000-4000-8000-00000000ba01';
export const VOUCHER_ID = 'b1ce0000-0000-4000-8000-00000000f001';
export const KIT_ID = 'b1ce0000-0000-4000-8000-00000000c1d1';
export const WARRANTY_ID = 'b1ce0000-0000-4000-8000-00000000ea01';
export const DELIVERY_ID = 'b1ce0000-0000-4000-8000-00000000de01';
// A supplier the owner never approved (no fiber exists at this id).
export const QUICKPARTS_ID = 'b1ce0000-0000-4000-8000-0000000bad01';
export const MANDATE_PKG = 'purchasing-mandate.machine';
export const SUPPLIER_ALIAS = 'spoke-and-sprocket.machine';
