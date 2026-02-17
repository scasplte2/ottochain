#!/usr/bin/env npx tsx
/**
 * ML0 Rejection Webhook Verification Test
 * 
 * This test verifies that the ML0 rejection webhook system is working correctly
 * by submitting invalid transactions and checking webhook delivery.
 */

import crypto from 'crypto';
import http from 'http';
import { URL } from 'url';

interface WebhookPayload {
  event: string;
  ordinal: number;
  timestamp: string;
  metagraphId: string;
  rejection: {
    updateType: string;
    fiberId: string;
    targetSequenceNumber?: number;
    errors: Array<{
      code: string;
      message: string;
    }>;
    signers: string[];
    updateHash: string;
  };
}

class WebhookTestServer {
  private server: http.Server;
  private receivedWebhooks: WebhookPayload[] = [];
  private port: number;

  constructor(port: number = 3000) {
    this.port = port;
    this.server = http.createServer((req, res) => {
      if (req.method === 'POST' && req.url === '/webhook') {
        let body = '';
        
        req.on('data', (chunk) => {
          body += chunk.toString();
        });

        req.on('end', () => {
          try {
            const webhook: WebhookPayload = JSON.parse(body);
            
            console.log('📥 Received webhook:', {
              event: webhook.event,
              ordinal: webhook.ordinal,
              updateType: webhook.rejection?.updateType,
              fiberId: webhook.rejection?.fiberId?.slice(0, 8) + '...',
              errors: webhook.rejection?.errors?.map(e => e.code),
              signersCount: webhook.rejection?.signers?.length || 0
            });

            // Verify webhook signature if present
            const signature = req.headers['x-ottochain-signature'] as string;
            if (signature) {
              const isValid = this.verifySignature(body, signature, 'test-secret');
              console.log('🔐 Signature valid:', isValid);
            }

            this.receivedWebhooks.push(webhook);
            res.writeHead(200);
            res.end('OK');
          } catch (err) {
            console.error('❌ Error parsing webhook:', err);
            res.writeHead(400);
            res.end('Bad Request');
          }
        });
      } else {
        res.writeHead(404);
        res.end('Not Found');
      }
    });
  }

  private verifySignature(body: string, signature: string, secret: string): boolean {
    const expectedSignature = 'sha256=' + 
      crypto.createHmac('sha256', secret)
        .update(body)
        .digest('hex');
    return signature === expectedSignature;
  }

  start(): Promise<void> {
    return new Promise((resolve) => {
      this.server.listen(this.port, () => {
        console.log(`🔗 Webhook server listening on port ${this.port}`);
        resolve();
      });
    });
  }

  stop(): Promise<void> {
    return new Promise((resolve) => {
      this.server.close(() => {
        console.log('🔗 Webhook server stopped');
        resolve();
      });
    });
  }

  getReceivedWebhooks(): WebhookPayload[] {
    return [...this.receivedWebhooks];
  }

  clearWebhooks(): void {
    this.receivedWebhooks = [];
  }
}

async function testRejectionWebhooks() {
  const webhookServer = new WebhookTestServer(3001);
  
  try {
    console.log('🚀 Starting ML0 Rejection Webhook Verification Test');
    console.log('');

    // Start webhook server
    await webhookServer.start();

    console.log('📋 Test Plan:');
    console.log('1. Webhook server: ✅ Started on port 3001');
    console.log('2. Submit invalid transaction (wrong signer)');
    console.log('3. Verify rejection webhook received');
    console.log('4. Check webhook payload structure');
    console.log('5. Verify error codes and messages');
    console.log('');

    // Instructions for manual testing
    console.log('🔧 Manual Test Steps:');
    console.log('');
    console.log('1. Configure ML0 node with webhook URL:');
    console.log('   WEBHOOK_URL=http://localhost:3001/webhook');
    console.log('   WEBHOOK_METAGRAPH_ID=DAG3KNyfeKUTuWpMMhormWgWSYMD1pDGB2uaWqxG');
    console.log('');
    console.log('2. Submit invalid transaction via Bridge API:');
    console.log('   curl -X POST https://bridge.ottochain.ai/sm/transition \\');
    console.log('     -H "Content-Type: application/json" \\');
    console.log('     -d \'{');
    console.log('       "fiberId": "00000000-0000-0000-0000-000000000001",');
    console.log('       "targetSequenceNumber": 999,');
    console.log('       "input": {},');
    console.log('       "privateKey": "wrong_private_key"');
    console.log('     }\'');
    console.log('');
    console.log('3. Expected webhook payload should include:');
    console.log('   - event: "transaction.rejected"');
    console.log('   - rejection.updateType: "TransitionStateMachine"');
    console.log('   - rejection.errors with codes like "NotSignedByOwner"');
    console.log('   - rejection.signers array');
    console.log('   - rejection.updateHash for deduplication');
    console.log('');

    // Wait for webhooks (in real scenario, would be triggered by invalid transactions)
    console.log('⏳ Waiting for webhook deliveries...');
    console.log('   (Submit invalid transactions via Bridge API now)');
    console.log('');

    // Wait 60 seconds for manual testing
    await new Promise(resolve => setTimeout(resolve, 60000));

    // Report results
    const webhooks = webhookServer.getReceivedWebhooks();
    console.log(`📊 Test Results: ${webhooks.length} webhooks received`);
    console.log('');

    if (webhooks.length > 0) {
      webhooks.forEach((webhook, index) => {
        console.log(`📥 Webhook ${index + 1}:`);
        console.log(`   Event: ${webhook.event}`);
        console.log(`   Ordinal: ${webhook.ordinal}`);
        console.log(`   Update Type: ${webhook.rejection.updateType}`);
        console.log(`   Fiber ID: ${webhook.rejection.fiberId}`);
        console.log(`   Errors: ${webhook.rejection.errors.map(e => e.code).join(', ')}`);
        console.log(`   Signers: ${webhook.rejection.signers.length}`);
        console.log(`   Update Hash: ${webhook.rejection.updateHash.slice(0, 16)}...`);
        console.log('');
      });

      // Verify webhook structure
      const firstWebhook = webhooks[0];
      const validations = [
        { check: firstWebhook.event === 'transaction.rejected', desc: 'Event type correct' },
        { check: typeof firstWebhook.ordinal === 'number', desc: 'Ordinal is number' },
        { check: typeof firstWebhook.timestamp === 'string', desc: 'Timestamp is string' },
        { check: typeof firstWebhook.metagraphId === 'string', desc: 'MetagraphId present' },
        { check: Array.isArray(firstWebhook.rejection.errors), desc: 'Errors is array' },
        { check: Array.isArray(firstWebhook.rejection.signers), desc: 'Signers is array' },
        { check: typeof firstWebhook.rejection.updateHash === 'string', desc: 'UpdateHash present' }
      ];

      console.log('✅ Webhook Structure Validation:');
      validations.forEach(v => {
        console.log(`   ${v.check ? '✅' : '❌'} ${v.desc}`);
      });

      const allValid = validations.every(v => v.check);
      console.log('');
      console.log(`🎯 Overall Result: ${allValid ? '✅ PASS' : '❌ FAIL'}`);

    } else {
      console.log('⚠️  No webhooks received');
      console.log('   This could mean:');
      console.log('   - Webhook URL not configured in ML0 node');
      console.log('   - No invalid transactions submitted');
      console.log('   - Network connectivity issues');
      console.log('   - ML0 node not running');
    }

  } finally {
    await webhookServer.stop();
  }
}

// Run test if called directly
if (require.main === module) {
  testRejectionWebhooks().catch(console.error);
}

export { WebhookTestServer, testRejectionWebhooks };