const bcrypt = require('bcryptjs');
const { Client } = require('pg');

async function run() {
    const hash = await bcrypt.hash('Admin@123', 12);
    const c = new Client({ connectionString: 'postgresql://postgres.ztwelnjnomimwywjpdjf:zEXBRe6XDN2QCbap@aws-0-eu-west-1.pooler.supabase.com:6543/postgres' });
    await c.connect();
    await c.query('UPDATE users SET password_hash = $1 WHERE email = $2', [hash, 'admin@monitor.local']);
    console.log('Password updated');
    await c.end();
}
run().catch(console.error);
