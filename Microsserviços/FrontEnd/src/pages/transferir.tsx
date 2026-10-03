import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Grid,
  TextField,
  Button,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  Alert,
  Chip,
  InputAdornment,
  Divider,
  Paper,
  CircularProgress,
  Stepper,
  Step,
  StepLabel,
  Snackbar,
} from '@mui/material';
import { useLocation, useNavigate } from 'react-router';
import SendIcon from '@mui/icons-material/Send';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import HubIcon from '@mui/icons-material/Hub';
import { useAuth } from '../context/AuthContext';
import { api } from '../services/api';
import { SagaStatusBadge } from '../components/SagaStatusBadge';
import { Conta, Transferencia } from '../types';

export default function TransferirPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const {
    activeConta,
    userContas,
    allContas,
    setActiveConta,
    refreshContas,
    refreshTransferencias,
  } = useAuth();

  // Se veio pré-preenchido pelo diretório de contas
  const prefillDestino = (location.state as any)?.destinoContaId;

  const [origemId, setOrigemId] = useState<number>(activeConta?.id || 0);
  const [destinoId, setDestinoId] = useState<string>(prefillDestino ? String(prefillDestino) : '');
  const [valor, setValor] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [transferenciaCriada, setTransferenciaCriada] = useState<Transferencia | null>(null);
  const [pollingStatus, setPollingStatus] = useState<boolean>(false);
  const [toast, setToast] = useState<{ open: boolean; message: string; severity: 'success' | 'error' | 'info' }>({
    open: false,
    message: '',
    severity: 'success',
  });

  useEffect(() => {
    if (activeConta && !origemId) {
      setOrigemId(activeConta.id);
    }
  }, [activeConta, origemId]);

  const contaOrigem = userContas.find((c) => c.id === origemId) || activeConta;
  const contasDestinoDisponiveis = allContas.filter((c) => c.id !== origemId);
  const contaDestinoInfo = allContas.find((c) => c.id === Number(destinoId));

  const formatCurrency = (val: number) => {
    return new Intl.NumberFormat('pt-BR', {
      style: 'currency',
      currency: 'BRL',
    }).format(val || 0);
  };

  const handleOrigemChange = (newOrigemId: number) => {
    setOrigemId(newOrigemId);
    const selected = userContas.find((c) => c.id === newOrigemId);
    if (selected) setActiveConta(selected);
  };

  const handleTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setTransferenciaCriada(null);

    const numValor = parseFloat(valor.replace(',', '.'));
    const numDestino = Number(destinoId);

    if (!origemId) {
      setError('Por favor, selecione uma conta de origem.');
      return;
    }

    if (!numDestino || numDestino <= 0) {
      setError('Por favor, informe a conta de destino.');
      return;
    }

    if (numDestino === origemId) {
      setError('A conta de destino não pode ser igual à conta de origem.');
      return;
    }

    if (isNaN(numValor) || numValor <= 0) {
      setError('Por favor, informe um valor positivo maior que zero.');
      return;
    }

    if (contaOrigem && numValor > contaOrigem.saldoValor) {
      setError(
        `Saldo insuficiente na conta de origem #${origemId}. Saldo disponível: ${formatCurrency(
          contaOrigem.saldoValor
        )}.`
      );
      return;
    }

    setLoading(true);
    try {
      const res = await api.transferencias.iniciar({
        contaOrigemId: origemId,
        contaDestinoId: numDestino,
        valor: numValor,
        moeda: contaOrigem?.saldoMoeda || 'BRL',
      });

      setTransferenciaCriada(res);
      setToast({
        open: true,
        message: `Transferência #${res.id} iniciada com sucesso via Saga!`,
        severity: 'success',
      });

      // Atualiza saldos das contas e lista de transferências
      await Promise.all([refreshContas(), refreshTransferencias()]);

      // Polling para acompanhar a conclusão da Saga distribuída
      if (res.status === 'INICIADA' || res.status === 'CONTA_ORIGEM_DEBITADA') {
        pollSagaStatus(res.id);
      }
    } catch (err: any) {
      console.error(err);
      setError(err.message || 'Erro ao processar transferência.');
    } finally {
      setLoading(false);
    }
  };

  const pollSagaStatus = async (id: number) => {
    setPollingStatus(true);
    let attempts = 0;
    const interval = setInterval(async () => {
      attempts++;
      try {
        const atual = await api.transferencias.obter(id);
        setTransferenciaCriada(atual);
        if (
          atual.status === 'CONCLUIDA' ||
          atual.status === 'DEBITO_FALHOU' ||
          atual.status === 'COMPENSADA' ||
          atual.status === 'COMPENSACAO_FALHOU' ||
          attempts >= 20
        ) {
          clearInterval(interval);
          setPollingStatus(false);
          await Promise.all([refreshContas(), refreshTransferencias()]);
        }
      } catch {
        clearInterval(interval);
        setPollingStatus(false);
      }
    }, 1500);
  };

  const getStepIndex = (status?: string) => {
    switch (status) {
      case 'INICIADA':
        return 1;
      case 'CONTA_ORIGEM_DEBITADA':
      case 'CREDITO_FALHOU':
        return 2;
      case 'CONCLUIDA':
      case 'COMPENSADA':
      case 'COMPENSACAO_FALHOU':
        return 3;
      default:
        return status ? 2 : 0;
    }
  };

  return (
    <Box sx={{ p: { xs: 2, sm: 3 }, maxWidth: 1000, mx: 'auto' }}>
      <Box sx={{ mb: 3 }}>
        <Typography variant="h5" sx={{ fontWeight: 800, color: '#1e293b' }}>
          Transferência entre Contas (Padrão Saga) 💸
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Envie saldo de forma segura através dos microsserviços orientados a eventos.
        </Typography>
      </Box>

      <Grid container spacing={3}>
        {/* Formulário de Transferência */}
        <Grid size={{ xs: 12, md: 7 }}>
          <Card elevation={2} sx={{ borderRadius: 3, border: '1px solid #e2e8f0' }}>
            <CardContent sx={{ p: { xs: 2.5, sm: 3.5 } }}>
              <Typography variant="h6" sx={{ fontWeight: 700, mb: 2 }}>
                Dados da Transferência
              </Typography>

              {error && (
                <Alert severity="error" sx={{ mb: 2.5 }}>
                  {error}
                </Alert>
              )}

              <Box component="form" onSubmit={handleTransfer}>
                {/* Conta de Origem */}
                <FormControl fullWidth sx={{ mb: 2.5 }}>
                  <InputLabel id="origem-select-label">Conta de Origem (Debitar de)</InputLabel>
                  <Select
                    labelId="origem-select-label"
                    value={origemId}
                    label="Conta de Origem (Debitar de)"
                    onChange={(e) => handleOrigemChange(Number(e.target.value))}
                  >
                    {userContas.map((c: Conta) => (
                      <MenuItem key={c.id} value={c.id}>
                        Conta #{c.id} - Saldo: {formatCurrency(c.saldoValor)} ({c.saldoMoeda})
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>

                {/* Card de Resumo da Origem */}
                {contaOrigem && (
                  <Box
                    sx={{
                      p: 2,
                      mb: 3,
                      borderRadius: 2,
                      bgcolor: '#f1f5f9',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                    }}
                  >
                    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      <AccountBalanceWalletIcon color="primary" />
                      <Box>
                        <Typography variant="caption" color="text.secondary">
                          Titular: {contaOrigem.nome}
                        </Typography>
                        <Typography variant="body2" sx={{ fontWeight: 700 }}>
                          Conta #{contaOrigem.id}
                        </Typography>
                      </Box>
                    </Box>
                    <Box sx={{ textAlign: 'right' }}>
                      <Typography variant="caption" color="text.secondary">
                        Saldo Disponível
                      </Typography>
                      <Typography variant="subtitle1" sx={{ fontWeight: 800, color: 'primary.main' }}>
                        {formatCurrency(contaOrigem.saldoValor)}
                      </Typography>
                    </Box>
                  </Box>
                )}

                <Divider sx={{ my: 2 }} />

                {/* Conta de Destino */}
                <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1, color: '#334155' }}>
                  Conta de Destino (Creditar para)
                </Typography>

                <FormControl fullWidth sx={{ mb: 1.5 }}>
                  <InputLabel id="destino-select-label">Selecione uma Conta no Banco</InputLabel>
                  <Select
                    labelId="destino-select-label"
                    value={destinoId}
                    label="Selecione uma Conta no Banco"
                    onChange={(e) => setDestinoId(e.target.value)}
                  >
                    {contasDestinoDisponiveis.map((c: Conta) => (
                      <MenuItem key={c.id} value={c.id}>
                        Conta #{c.id} - {c.nome} ({formatCurrency(c.saldoValor)})
                      </MenuItem>
                    ))}
                  </Select>
                </FormControl>

                <TextField
                  fullWidth
                  label="Ou digite o ID da Conta de Destino"
                  type="number"
                  placeholder="Ex: 2"
                  value={destinoId}
                  onChange={(e) => setDestinoId(e.target.value)}
                  sx={{ mb: 2 }}
                />

                {contaDestinoInfo && (
                  <Paper
                    variant="outlined"
                    sx={{
                      p: 1.5,
                      mb: 2.5,
                      bgcolor: '#f8fafc',
                      borderRadius: 2,
                      display: 'flex',
                      alignItems: 'center',
                      gap: 1.5,
                    }}
                  >
                    <CheckCircleOutlineIcon color="success" />
                    <Box>
                      <Typography variant="caption" color="text.secondary">
                        Destinatário Confirmado:
                      </Typography>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>
                        Conta #{contaDestinoInfo.id} - {contaDestinoInfo.nome}
                      </Typography>
                    </Box>
                  </Paper>
                )}

                {/* Valor */}
                <Typography variant="subtitle2" sx={{ fontWeight: 700, mb: 1, color: '#334155' }}>
                  Valor da Transferência
                </Typography>

                <TextField
                  fullWidth
                  type="number"
                  placeholder="0,00"
                  value={valor}
                  onChange={(e) => setValor(e.target.value)}
                  InputProps={{
                    startAdornment: <InputAdornment position="start">R$</InputAdornment>,
                  }}
                  helperText="Informe o montante em reais que deseja transferir"
                  sx={{ mb: 1.5 }}
                />

                {/* Atalhos de valores */}
                <Box sx={{ display: 'flex', gap: 1, mb: 3, flexWrap: 'wrap' }}>
                  {[20, 50, 100, 200, 500].map((quickVal) => (
                    <Chip
                      key={quickVal}
                      label={`+ R$ ${quickVal}`}
                      clickable
                      variant="outlined"
                      size="small"
                      onClick={() => setValor(String(quickVal))}
                    />
                  ))}
                  {contaOrigem && (
                    <Chip
                      label="Transferir Saldo Todo"
                      clickable
                      color="primary"
                      variant="outlined"
                      size="small"
                      onClick={() => setValor(String(contaOrigem.saldoValor))}
                    />
                  )}
                </Box>

                <Button
                  type="submit"
                  fullWidth
                  variant="contained"
                  size="large"
                  disabled={loading || !valor || !destinoId}
                  startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <SendIcon />}
                  sx={{
                    py: 1.5,
                    borderRadius: 2.5,
                    fontWeight: 700,
                    fontSize: '1rem',
                    textTransform: 'none',
                    background: 'linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%)',
                  }}
                >
                  {loading ? 'Processando Saga...' : 'Executar Transferência'}
                </Button>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        {/* Coluna da Direita: Acompanhamento da Saga e Explicação da Arquitetura */}
        <Grid size={{ xs: 12, md: 5 }}>
          {/* Status em Tempo Real da Transferência Recente */}
          {transferenciaCriada && (
            <Card
              elevation={2}
              sx={{
                borderRadius: 3,
                border: '2px solid #3b82f6',
                mb: 3,
                bgcolor: '#f0fdf4',
              }}
            >
              <CardContent sx={{ p: 2.5 }}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 1.5 }}>
                  <Typography variant="subtitle1" sx={{ fontWeight: 800, color: '#166534' }}>
                    Transferência #{transferenciaCriada.id}
                  </Typography>
                  <SagaStatusBadge status={transferenciaCriada.status} />
                </Box>

                <Typography variant="body2" sx={{ mb: 2 }}>
                  Valor transferido: <strong>{formatCurrency(transferenciaCriada.valor)}</strong> da
                  Conta <strong>#{transferenciaCriada.contaOrigemId}</strong> para Conta{' '}
                  <strong>#{transferenciaCriada.contaDestinoId}</strong>.
                </Typography>

                {pollingStatus && (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, my: 1.5 }}>
                    <CircularProgress size={16} />
                    <Typography variant="caption" color="text.secondary">
                      Verificando conclusão dos eventos nos microsserviços...
                    </Typography>
                  </Box>
                )}

                <Stepper
                  activeStep={getStepIndex(transferenciaCriada.status)}
                  orientation="vertical"
                  sx={{ mt: 1 }}
                >
                  <Step completed>
                    <StepLabel>Transferência Iniciada (TransferenciaService)</StepLabel>
                  </Step>
                  <Step
                    completed={
                      transferenciaCriada.status === 'CONTA_ORIGEM_DEBITADA' ||
                      transferenciaCriada.status === 'CONCLUIDA' ||
                      transferenciaCriada.status === 'CREDITO_FALHOU' ||
                      transferenciaCriada.status === 'COMPENSADA' ||
                      transferenciaCriada.status === 'COMPENSACAO_FALHOU'
                    }
                  >
                    <StepLabel>Débito na Conta Origem (ContaService)</StepLabel>
                  </Step>
                  <Step completed={transferenciaCriada.status === 'CONCLUIDA'}>
                    <StepLabel>
                      {transferenciaCriada.status === 'COMPENSADA'
                        ? 'Crédito Falhou & Estorno Concluído'
                        : 'Crédito no Destino & Conclusão da Saga'}
                    </StepLabel>
                  </Step>
                </Stepper>

                {transferenciaCriada.status === 'COMPENSADA' && (
                  <Alert severity="warning" sx={{ mt: 2 }}>
                    O crédito no destino falhou. O rollback da Saga (compensação) foi executado com sucesso e o saldo foi estornado para a conta de origem!
                  </Alert>
                )}

                {transferenciaCriada.status === 'CREDITO_FALHOU' && (
                  <Alert severity="info" sx={{ mt: 2 }}>
                    O crédito no destino falhou. Processando estorno (compensação) para a conta de origem...
                  </Alert>
                )}

                {transferenciaCriada.status === 'DEBITO_FALHOU' && (
                  <Alert severity="error" sx={{ mt: 2 }}>
                    O débito inicial falhou (saldo insuficiente ou erro na conta). Nenhuma alteração foi realizada.
                  </Alert>
                )}

                <Button
                  fullWidth
                  variant="outlined"
                  size="small"
                  sx={{ mt: 2 }}
                  onClick={() => navigate('/extrato')}
                >
                  Ver no Extrato
                </Button>
              </CardContent>
            </Card>
          )}

          {/* Painel Informativo sobre a Arquitetura Saga de Microsserviços */}
          <Card elevation={1} sx={{ borderRadius: 3, border: '1px solid #e2e8f0' }}>
            <CardContent sx={{ p: 2.5 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
                <HubIcon color="primary" />
                <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
                  Como funciona esta operação?
                </Typography>
              </Box>

              <Typography variant="body2" color="text.secondary" paragraph>
                Esta transferência executa o <strong>Padrão Saga</strong> entre microsserviços:
              </Typography>

              <Box component="ol" sx={{ pl: 2, m: 0, '& li': { mb: 1, fontSize: '0.85rem', color: '#475569' } }}>
                <li>
                  O <strong>Transferencia-Service</strong> cria a transação com status{' '}
                  <code>INICIADA</code> e envia comando de débito para o <strong>Conta-Service</strong>.
                </li>
                <li>
                  O <strong>Conta-Service</strong> valida o saldo, debita a conta de origem e dispara o
                  evento <code>ContaDebitada</code> via Apache Kafka.
                </li>
                <li>
                  O <strong>TransferenciaProcessManager</strong> orquestra o crédito na conta de destino.
                </li>
                <li>
                  Com o crédito concluído, a Saga atinge o estado final <code>CONCLUIDA</code>!
                </li>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {/* Snackbar feedback */}
      <Snackbar
        open={toast.open}
        autoHideDuration={4000}
        onClose={() => setToast((prev) => ({ ...prev, open: false }))}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert
          onClose={() => setToast((prev) => ({ ...prev, open: false }))}
          severity={toast.severity}
          sx={{ width: '100%', fontWeight: 600 }}
        >
          {toast.message}
        </Alert>
      </Snackbar>
    </Box>
  );
}
