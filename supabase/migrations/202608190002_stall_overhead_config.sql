-- Add dynamic overhead_config column to stalls table.
-- Allows managers to configure dynamic daily fixed operating expenses (e.g. cashier pay, rent, electricity, water).

alter table public.stalls
  add column if not exists overhead_config jsonb not null default '[
    {"key": "cashier", "label": "Cashier pay", "dailyRate": 500.00, "icon": "👨‍💼", "description": "Daily cashier wage"},
    {"key": "rent", "label": "Stall rent", "dailyRate": 200.00, "icon": "🏪", "description": "Daily space/booth rental"},
    {"key": "electricity", "label": "Electricity", "dailyRate": 33.33, "icon": "⚡", "description": "Power for freezers & lighting"},
    {"key": "water", "label": "Water", "dailyRate": 10.00, "icon": "💧", "description": "Sanitation & cleaning water"}
  ]'::jsonb;
