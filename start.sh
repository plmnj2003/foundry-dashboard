#!/bin/zsh
set -e
cd "$(dirname "$0")"

# Load .env
if [ -f .env ]; then
  export $(grep -v '^#' .env | xargs)
fi

export PATH="/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"

# Start PostgreSQL if not running
if ! pg_isready -q 2>/dev/null; then
  brew services start postgresql@16
  sleep 3
fi

# Start backend
echo "Starting backend..."
pkill -f "dashboard-0.0.1-SNAPSHOT.jar" 2>/dev/null || true
sleep 1
java -jar backend/target/dashboard-0.0.1-SNAPSHOT.jar > /tmp/foundry-backend.log 2>&1 &
BACKEND_PID=$!

# Wait for backend
echo "Waiting for backend (PID $BACKEND_PID)..."
for i in {1..20}; do
  curl -s http://localhost:8080/api/kpis > /dev/null 2>&1 && break
  sleep 1
done

echo "Backend ready."

# Start frontend
echo "Starting frontend..."
pkill -f "vite" 2>/dev/null || true
cd frontend
npm run dev > /tmp/foundry-frontend.log 2>&1 &
FRONTEND_PID=$!
cd ..

echo ""
echo "✅ Foundry Dashboard running"
echo "   Frontend: http://localhost:5173"
echo "   Backend:  http://localhost:8080"
echo "   Backend PID: $BACKEND_PID | Frontend PID: $FRONTEND_PID"
echo ""
echo "To stop: kill $BACKEND_PID $FRONTEND_PID"
