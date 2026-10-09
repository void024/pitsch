import { expect, test } from '@playwright/test';
import { mockApi } from './mockApi.ts';

test('sign in, open a pitch that needs a decision, read the brief and approve the reply', async ({ page }) => {
  const api = await mockApi(page);

  await page.goto('/workflows/42');
  // Anonymous deep link → login, remembering where to go back to.
  await expect(page).toHaveURL(/\/login\?returnTo=%2Fworkflows%2F42/);

  await page.getByLabel('Email').fill('ada@northwind.vc');
  await page.getByLabel('Password').fill('wrong-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText('Incorrect email or password.')).toBeVisible();

  await page.getByLabel('Password').fill('correct-horse-42');
  await page.getByRole('button', { name: 'Sign in' }).click();

  // Back on the workflow; the reply tab opens because the Email Agent produced a draft.
  await expect(page).toHaveURL(/\/workflows\/42$/);
  await expect(page.getByRole('heading', { name: 'Acme Robotics' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Reply' })).toHaveAttribute('aria-selected', 'true');

  await page.getByRole('tab', { name: 'Brief' }).click();
  await expect(page.getByText(/not investment advice/i)).toBeVisible();
  await expect(page.getByText('Partially supported')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Trade article' })).toHaveAttribute('rel', /noopener/);

  await page.getByRole('tab', { name: 'Reply' }).click();
  await page.getByLabel('Message').fill('Thanks Grace — could you share the deck and your NRR?');
  await page.getByRole('button', { name: 'Review and send' }).click();
  await expect(page.getByRole('dialog')).toContainText('founder@acme.example');
  await page.getByRole('button', { name: 'Approve and send' }).click();

  await expect(page.getByText('Sent', { exact: true })).toBeVisible();
  expect(api.sendRequests).toHaveLength(1);
  expect(api.sendRequests[0].idempotencyKey).toMatch(/^send-/);
  expect(api.sendRequests[0].body).toMatchObject({ body: 'Thanks Grace — could you share the deck and your NRR?' });
  expect(api.unmatched).toEqual([]);
});

test('the dashboard lists work that needs a decision', async ({ page }) => {
  const api = await mockApi(page);
  await page.goto('/login');
  await page.getByLabel('Email').fill('ada@northwind.vc');
  await page.getByLabel('Password').fill('correct-horse-42');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole('heading', { name: /Ada$/ })).toBeVisible();
  await expect(page.getByText('Need your decision')).toBeVisible();
  await page.getByRole('link', { name: /Acme Robotics/ }).click();
  await expect(page).toHaveURL(/\/workflows\/42$/);
  expect(api.unmatched).toEqual([]);
});

test('an expired session sends the user back to sign in', async ({ page }) => {
  await mockApi(page);
  await page.goto('/pitches');
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});
