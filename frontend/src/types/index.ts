export interface ReleaseSummary {
  id: number;
  title: string;
  artistId: number;
  artistName: string;
  releaseType: string;
  releaseDate: string;
  averageRating: number;
  totalRatings: number;
}

export interface ChartEntry {
  rank: number;
  releaseId: number;
  title: string;
  artistName: string;
  releaseYear: number;
  bayesianScore: number;
  weightedAvgRating: number;
  totalRatings: number;
  isEsoteric: boolean;
}

export interface TopChartResponse {
  chartType: string;
  lastUpdated: string;
  entries: ChartEntry[];
}

export interface ChartFilter {
  genreId?: number;
  year?: number;
  releaseType?: string;
  includeEsoteric?: boolean;
  limit?: number;
}
