import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router-dom";
import { Toaster } from "sonner";

import { AuthProvider, RequireAuth } from "@/lib/auth";
import { AppShell } from "@/components/layout/AppShell";
import { LoginPage } from "@/pages/LoginPage";
import { DashboardPage } from "@/pages/DashboardPage";
import { CampaignsPage } from "@/pages/CampaignsPage";
import { CampaignWizardPage } from "@/pages/CampaignWizardPage";
import { CampaignDetailPage } from "@/pages/CampaignDetailPage";
import { TemplatesPage } from "@/pages/TemplatesPage";
import { TemplateEditorPage } from "@/pages/TemplateEditorPage";
import { ChannelsPage } from "@/pages/ChannelsPage";
import { ApiKeysPage } from "@/pages/ApiKeysPage";
import { DevelopersPage } from "@/pages/DevelopersPage";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 10_000 },
  },
});

const router = createBrowserRouter([
  { path: "/login", element: <LoginPage /> },
  {
    path: "/",
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: "campaigns", element: <CampaignsPage /> },
      { path: "campaigns/new", element: <CampaignWizardPage /> },
      { path: "campaigns/:id", element: <CampaignDetailPage /> },
      { path: "templates", element: <TemplatesPage /> },
      { path: "templates/new", element: <TemplateEditorPage /> },
      { path: "templates/:id", element: <TemplateEditorPage /> },
      { path: "channels", element: <ChannelsPage /> },
      { path: "api-keys", element: <ApiKeysPage /> },
      { path: "developers", element: <DevelopersPage /> },
      { path: "*", element: <Navigate to="/" replace /> },
    ],
  },
]);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
        <Toaster
          position="bottom-left"
          toastOptions={{
            className: "!rounded !bg-[#323232] !text-white !border-0 !shadow-menu !text-sm",
            duration: 5000,
          }}
        />
      </AuthProvider>
    </QueryClientProvider>
  );
}
