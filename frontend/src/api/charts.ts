import client from './client';
import { TopChartResponse, ChartFilter } from '../types';

export const getTopChart = async (filter: ChartFilter): Promise<TopChartResponse> => {
  const params = { ...filter };
  const response = await client.get<TopChartResponse>('/charts/top', { params });
  return response.data;
};
